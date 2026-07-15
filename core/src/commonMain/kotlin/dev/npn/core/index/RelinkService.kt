package dev.npn.core.index

import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SegmentWriter

/**
 * Applies a relink: pairs a superseded (old) document with the new document that replaced it after
 * an external edit / bake (PLAN.md sec. 2). Emits a `relink` journal event via [SegmentWriter] so
 * the pairing syncs and folds deterministically, then triggers a rescan so the fold carries the old
 * document's metadata onto the new id and marks the old doc superseded (see [Indexer.applyFold]).
 */
class RelinkService(
    private val writer: SegmentWriter,
    private val indexer: Indexer,
) {
    /**
     * Record oldDocId -> newDocId and refold. Returns the [ScanReport] of the triggered rescan so
     * callers can observe the resulting state.
     */
    suspend fun applyRelink(oldDocId: String, newDocId: String): ScanReport {
        writer.commit(listOf(PendingOp.relink(oldDocId, newDocId)))
        return indexer.rescan()
    }
}
