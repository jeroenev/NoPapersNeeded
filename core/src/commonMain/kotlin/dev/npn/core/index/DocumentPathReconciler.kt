package dev.npn.core.index

import androidx.room3.withWriteTransaction
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.FtsDocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.DocumentPaths
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.FoldedState
import dev.npn.core.model.SystemProperty
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
) {
    private val paths = DocumentPaths(fs)

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

    /** Where a fold leaves what it projected: the work of the [deferring] call around it. */
    fun work(): PathWork = checkNotNull(open) { "A fold runs inside DocumentPathReconciler.deferring" }

    /** Whether [row] sits somewhere other than where its winning path intent wants it. */
    fun pending(
        state: FoldedState,
        row: DocumentEntity,
    ): Boolean = intendedPath(state, row.docId)?.let { it != row.relPath } ?: false

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
            val relocated = if (pending(state, row)) project(row, state) else row
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

    private suspend fun project(
        row: DocumentEntity,
        state: FoldedState,
    ): DocumentEntity {
        val intended = intendedPath(state, row.docId) ?: return row
        if (intended == row.relPath) return row
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

    private suspend fun releaseStaleOwners(
        state: FoldedState,
        rows: MutableMap<String, DocumentEntity>,
    ) {
        val targets =
            rows.values.mapNotNull { row ->
                intendedPath(state, row.docId)
                    ?.takeIf { it != row.relPath && DocumentPaths.isDocumentPath(it) }
                    ?.let { row.docId to it }
            }
        for ((docId, intended) in targets) {
            val owner =
                db
                    .documentDao()
                    .getByRelPath(intended)
                    ?.takeIf { it.docId != docId && (it.missing || it.deleted) } ?: continue
            if (!fs.exists(intended)) {
                val released = owner.copy(relPath = missingSentinel(owner.docId), missing = true)
                db.documentDao().upsert(released)
                if (owner.docId in rows) rows[owner.docId] = released
            }
        }
    }

    suspend fun conflicts(state: FoldedState): List<PathConflict> =
        db.documentDao().allPathsAndMeta().mapNotNull { row ->
            val intended = intendedPath(state, row.docId)
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
            val intended = intendedPath(state, duplicate.docId)
            val row = rows[duplicate.docId]
            if (intended != null && row?.relPath == intended) {
                reconcileAvailable(duplicate.docId, duplicate.extraRelPath, intended)
            }
            if (fs.exists(duplicate.extraRelPath) && duplicate.extraRelPath != row?.relPath) retained.add(duplicate)
        }
        duplicates.clear()
        duplicates.addAll(retained)
    }

    private fun intendedPath(
        state: FoldedState,
        docId: String,
    ): String? {
        if (state.isTrashed(docId) || state.isDeleted(docId) || state.resolveDocId(docId) != docId) return null
        return state.effectiveProp(docId, SystemProperty.STORAGE_PATH)
    }

    private suspend fun reconcileAvailable(
        docId: String,
        source: String,
        target: String,
    ): String =
        // Physical failures leave the journal intent pending, surfaced by conflicts(). They must
        // not prevent unrelated documents and metadata from being projected.
        runCatchingCancellable { paths.reconcile(docId, source, target) }.getOrDefault(source)

    companion object {
        /**
         * A synthetic, unique relPath for a row that lost its path while its document waits (a
         * file replaced in place, an owner released for an intent). It lives under `.npn/` so it
         * is never indexed, and encodes the docId to satisfy the unique relPath constraint.
         */
        fun missingSentinel(docId: String): String = "${NpnPaths.NPN_DIR}/missing/$docId"
    }
}
