package dev.npn.core.index

import androidx.room3.withWriteTransaction
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsDocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.isLive
import dev.npn.core.db.missingSentinel
import dev.npn.core.fs.DocumentPaths
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.FoldedState
import dev.npn.core.util.IdSource
import dev.npn.core.util.runCatchingCancellable

/**
 * Materializes winning path intents while keeping the index pointed at verified, available bytes.
 *
 * All of it is filesystem work, so none of it runs inside a fold: a fold projects rows from the
 * journal alone and leaves them in the [PathWork] of the [deferring] call around it, which moves
 * the files those rows' intents ask for once the fold's transaction has committed.
 */
internal class DocumentPathReconciler(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    /** Where this device last saw each settled document; the rescan detects external moves from it. */
    private val externalMoves: ExternalDocumentMoves,
    ids: IdSource = IdSource.Random,
) {
    private val paths = DocumentPaths(fs, ids)

    /** The work of the outermost [deferring] call in progress; null outside one. */
    private var open: PathWork? = null

    /**
     * What the folds inside one [deferring] call leave for after their transaction: the rows they
     * projected, as written, and the searchable columns of those whose path intent is still pending,
     * so a move can rewrite the file name. [known] holds content hashes a rescan established, by
     * path, for observing them without hashing again.
     */
    class PathWork(
        val known: Map<String, String>,
    ) {
        var state: FoldedState? = null
        val rows = LinkedHashMap<String, DocumentEntity>()
        val searchable = HashMap<String, FtsDocumentEntity>()
    }

    /** Whether a [deferring] call is in progress, so a fold's path work would wait for it. */
    val collecting: Boolean get() = open != null

    /**
     * Run [block], letting every fold in it leave its rows in [work], then settle them. A nested
     * call joins the outermost one, so the files move only once its transaction has committed.
     */
    suspend fun <T> deferring(
        known: Map<String, String> = emptyMap(),
        block: suspend () -> T,
    ): T {
        if (open != null) return block()
        val work = PathWork(known)
        open = work
        val result =
            try {
                block()
            } finally {
                open = null
            }
        settle(work)
        return result
    }

    /**
     * Where a fold over [state] leaves what it projected: the work of the [deferring] call around
     * it, which settles against the state of its last fold.
     */
    fun work(state: FoldedState): PathWork =
        checkNotNull(open) { "A fold runs inside DocumentPathReconciler.deferring" }.also { it.state = state }

    /** Whether [row] sits somewhere other than where its winning path intent wants it. */
    fun pending(
        state: FoldedState,
        row: DocumentEntity,
    ): Boolean = state.intendedPath(row.docId)?.let { it != row.relPath } ?: false

    /**
     * The filesystem side of the folds in [work]: free intended paths held by stale rows, move each
     * document whose intent differs from where it is, record where every document was seen, and
     * write the moved rows: their location and their searchable file name.
     */
    private suspend fun settle(work: PathWork) {
        val state = work.state ?: return
        val current = LinkedHashMap(work.rows)
        releaseStaleOwners(state, current)
        val verified = HashMap(work.known)
        val moved = ArrayList<DocumentEntity>()
        for (row in current.values.toList()) {
            val intended = state.intendedPath(row.docId)?.takeIf { it != row.relPath } ?: continue
            val relocated = project(row, intended)
            if (relocated != row) {
                current[row.docId] = relocated
                // The move checked the bytes it placed, so observing them needs no second hash.
                verified[relocated.relPath] = relocated.docId
                moved += relocated
            }
        }
        externalMoves.observe(current.values, state, verified)
        if (moved.isEmpty()) return
        db.withWriteTransaction {
            for (row in moved) db.documentDao().relocate(row.docId, row.relPath, row.fileName, row.size, row.mtime)
            db.ftsDao().upsertMetadata(moved.mapNotNull { work.searchable[it.docId]?.copy(fileName = it.fileName) })
        }
    }

    /** Move [row]'s file to [intended], the path its intent asks for, where nothing else holds it. */
    private suspend fun project(
        row: DocumentEntity,
        intended: String,
    ): DocumentEntity {
        // Metadata can arrive before a scan updates another document's old path in the cache.
        val owner = db.documentDao().getByRelPath(intended)
        if (owner != null && owner.docId != row.docId) return row
        val actual = reconcileAvailable(row.docId, row.relPath, intended)
        if (actual == row.relPath) return row
        val meta = fs.stat(actual)
        return if (meta == null) {
            row
        } else {
            row.copy(
                relPath = actual,
                fileName = meta.name,
                size = meta.size,
                mtime = meta.mtimeMillis,
                missing = false,
            )
        }
    }

    /**
     * Free each intended path in [rows] that a hidden row (trashed, deleted or missing) holds while
     * nothing is on disk there, the way [dev.npn.core.db.DocumentDao.upsertAll] frees one for a live
     * row: the holder waits at its [missingSentinel] with its flags kept. A trashed document's
     * restore goes by its `trashedOrigPath`, not by its row's path.
     */
    private suspend fun releaseStaleOwners(
        state: FoldedState,
        rows: MutableMap<String, DocumentEntity>,
    ) {
        val targets =
            rows.values.mapNotNull { row ->
                state
                    .intendedPath(row.docId)
                    ?.takeIf { it != row.relPath && NpnPaths.isDocumentPath(it) }
                    ?.let { row.docId to it }
            }
        for ((docId, intended) in targets) {
            val owner =
                db
                    .documentDao()
                    .getByRelPath(intended)
                    ?.takeIf { it.docId != docId && !it.isLive } ?: continue
            if (!fs.exists(intended)) {
                val released = owner.copy(relPath = missingSentinel(owner.docId))
                db.documentDao().upsert(released)
                if (owner.docId in rows) rows[owner.docId] = released
            }
        }
    }

    suspend fun conflicts(state: FoldedState): List<PathConflict> =
        db.documentDao().allPathsAndMeta().mapNotNull { row ->
            val intended = state.intendedPath(row.docId)
            if (intended == null || intended == row.relPath) null else PathConflict(row.docId, row.relPath, intended)
        }

    /**
     * Retire each extra copy in [duplicates] whose document already sits at its intended path, and
     * keep in the list only the copies still on disk. The caller records what is left.
     */
    suspend fun reconcileDuplicates(
        state: FoldedState,
        duplicates: MutableList<DuplicateReport>,
    ) {
        val rows = db.documentDao().getByDocIds(duplicates.map { it.docId }.distinct()).associateBy { it.docId }
        val retained = ArrayList<DuplicateReport>()
        for (duplicate in duplicates) {
            val intended = state.intendedPath(duplicate.docId)
            val row = rows[duplicate.docId]
            if (intended != null && row?.relPath == intended) {
                reconcileAvailable(duplicate.docId, duplicate.extraRelPath, intended)
            }
            if (fs.exists(duplicate.extraRelPath) && duplicate.extraRelPath != row?.relPath) retained.add(duplicate)
        }
        duplicates.clear()
        duplicates.addAll(retained)
    }

    private suspend fun reconcileAvailable(
        docId: String,
        source: String,
        target: String,
    ): String =
        // Physical failures leave the journal intent pending, surfaced by conflicts(). They must
        // not prevent unrelated documents and metadata from being projected.
        runCatchingCancellable { paths.reconcile(docId, source, target) }.getOrDefault(source)
}
