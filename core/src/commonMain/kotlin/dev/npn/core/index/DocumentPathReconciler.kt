package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
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
 * All of it is filesystem work, so none of it runs inside a fold: the fold projects rows from the
 * journal alone, and [settle] moves the files those rows' intents ask for once the fold's
 * transaction has committed.
 */
internal class DocumentPathReconciler(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
) {
    private val paths = DocumentPaths(fs)
    private val externalMoves = ExternalDocumentMoves(fs, db.keyValueDao())

    /** Whether [row] sits somewhere other than where its winning path intent wants it. */
    fun pending(
        state: FoldedState,
        row: DocumentEntity,
    ): Boolean = intendedPath(state, row.docId)?.let { it != row.relPath } ?: false

    /**
     * The filesystem side of a fold that projected [rows]: free intended paths held by stale rows,
     * move each document whose intent differs from where it is, and record where every document
     * was seen. [known] maps paths to content hashes the caller already verified (a rescan's), so
     * observing them hashes nothing again. Returns the rows it moved, for the caller to write.
     */
    suspend fun settle(
        state: FoldedState,
        rows: Collection<DocumentEntity>,
        known: Map<String, String>,
    ): List<DocumentEntity> {
        val current = rows.associateByTo(LinkedHashMap()) { it.docId }
        releaseStaleOwners(state, current)
        val verified = HashMap(known)
        val moved = ArrayList<DocumentEntity>()
        for (row in current.values.toList()) {
            if (!pending(state, row)) continue
            val relocated = project(row, state)
            if (relocated == row) continue
            current[row.docId] = relocated
            // The move checked the bytes it published, so observing them needs no second hash.
            verified[relocated.relPath] = relocated.docId
            moved += relocated
        }
        externalMoves.observe(current.values, state, verified)
        return moved
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
                val released = owner.copy(relPath = "${NpnPaths.NPN_DIR}/missing/${owner.docId}", missing = true)
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

    suspend fun reconcileDuplicates(
        state: FoldedState,
        duplicates: MutableList<DuplicateReport>,
    ) {
        val retained = ArrayList<DuplicateReport>()
        for (duplicate in duplicates) {
            val intended = intendedPath(state, duplicate.docId)
            val row = db.documentDao().getByDocId(duplicate.docId)
            if (intended != null && row?.relPath == intended) {
                reconcileAvailable(duplicate.docId, duplicate.extraRelPath, intended)
            }
            if (fs.exists(duplicate.extraRelPath) && duplicate.extraRelPath != row?.relPath) retained.add(duplicate)
            db.duplicateDao().replaceAll(duplicate.docId, emptyList())
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
}
