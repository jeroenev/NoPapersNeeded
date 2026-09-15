package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.PathMeta
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Device-local observations distinguish external moves from unapplied journal path changes. */
internal class ExternalDocumentMoves(
    private val fs: LibraryFileSystem,
    private val kv: KeyValueDao,
) {
    @Serializable
    private data class Baseline(
        val path: String,
        val version: Hlc?,
    )

    suspend fun observe(
        row: DocumentEntity,
        state: FoldedState,
    ) {
        if (row.missing || row.deleted || row.trashed) return
        val intended = state.effectiveProp(row.docId, SystemProperty.STORAGE_PATH)
        if (intended != null && intended != row.relPath) return
        val value = encode(row.docId, row.relPath, state)
        if (kv.get(key(row.docId)) == value) return
        // A metadata-only refresh must not bless an intent whose bytes haven't arrived yet.
        if (fs.exists(row.relPath) && fs.sha256(row.relPath) == row.docId) kv.put(key(row.docId), value)
    }

    suspend fun detect(
        state: FoldedState,
        before: List<PathMeta>,
        files: List<FileMeta>,
        hashes: Map<String, String>,
    ): List<PendingOp> {
        val known = before.associateBy { it.relPath }
        val paths = files.map { it.relPath }.toSet()
        val byHash = files.groupBy { hashes[it.relPath] ?: known[it.relPath]?.docId }
        val moves = ArrayList<PendingOp>()
        for (old in before) {
            val target = byHash[old.docId]?.singleOrNull()?.relPath ?: continue
            if (eligible(state, old, target, paths)) {
                moves += PendingOp.propSet(old.docId, SystemProperty.STORAGE_PATH, target)
            }
        }
        return moves
    }

    private suspend fun eligible(
        state: FoldedState,
        old: PathMeta,
        target: String,
        paths: Set<String>,
    ): Boolean {
        if (old.relPath in paths || old.relPath == target ||
            target.substringAfterLast('/').startsWith("npn-recovery-")
        ) {
            return false
        }
        if (state.isDeleted(old.docId) || state.isTrashed(old.docId) ||
            state.resolveDocId(old.docId) != old.docId
        ) {
            return false
        }
        if (kv.get(key(old.docId)) != encode(old.docId, old.relPath, state)) return false
        // An omitted listing entry is not evidence of a move. Recheck both endpoints before writing.
        return !fs.exists(old.relPath) && fs.sha256(target) == old.docId
    }

    private fun encode(
        docId: String,
        path: String,
        state: FoldedState,
    ): String = Json.encodeToString(Baseline(path, state.propVersion(docId, SystemProperty.STORAGE_PATH)))

    private fun key(docId: String): String = "observed-document-path:$docId"
}
