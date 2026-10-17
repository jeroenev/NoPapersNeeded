package dev.npn.core.index

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.KeyValueDao
import dev.npn.core.db.KeyValueEntity
import dev.npn.core.db.PathMeta
import dev.npn.core.db.isLive
import dev.npn.core.fs.DocumentPaths
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.PendingOp
import dev.npn.core.model.SystemProperty
import dev.npn.core.util.NpnJson
import kotlinx.serialization.Serializable

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

    /**
     * Record where each of [rows] was seen, under its current path intent. The stored baselines are
     * read in one bulk pass, and only a row whose baseline moved is checked against its bytes:
     * through [known] (path to content hash) when the caller already knows the path's hash, by
     * hashing the file otherwise.
     */
    suspend fun observe(
        rows: Collection<DocumentEntity>,
        state: FoldedState,
        known: Map<String, String>,
    ) {
        val observable =
            rows.filter { row ->
                val intended = state.inheritedProp(row.docId, SystemProperty.STORAGE_PATH)
                row.isLive && (intended == null || intended == row.relPath)
            }
        if (observable.isEmpty()) return
        val stored = kv.getAll(observable.map { key(it.docId) })
        val baselines = ArrayList<KeyValueEntity>()
        for (row in observable) {
            val value = encode(row.docId, row.relPath, state)
            if (stored[key(row.docId)] == value) continue
            // A metadata-only refresh must not bless an intent whose bytes haven't arrived yet.
            if (holds(row, known)) baselines += KeyValueEntity(key(row.docId), value)
        }
        if (baselines.isNotEmpty()) kv.putAll(baselines)
    }

    private suspend fun holds(
        row: DocumentEntity,
        known: Map<String, String>,
    ): Boolean {
        known[row.relPath]?.let { return it == row.docId }
        return fs.exists(row.relPath) && fs.sha256(row.relPath) == row.docId
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
            NpnPaths.fileName(target).startsWith(DocumentPaths.RECOVERY_PREFIX)
        ) {
            return false
        }
        if (!state.isCurrent(old.docId)) return false
        if (kv.get(key(old.docId)) != encode(old.docId, old.relPath, state)) return false
        // An omitted listing entry is not evidence of a move. Recheck both endpoints before writing.
        return !fs.exists(old.relPath) && fs.sha256(target) == old.docId
    }

    private fun encode(
        docId: String,
        path: String,
        state: FoldedState,
    ): String =
        NpnJson.Persisted.encodeToString(Baseline(path, state.inheritedPropVersion(docId, SystemProperty.STORAGE_PATH)))

    private fun key(docId: String): String = "observed-document-path:$docId"
}
