package dev.npn.core.index

import dev.npn.core.app.JournalCoordinator
import dev.npn.core.journal.PendingOp

/**
 * Applies a relink: pairs a superseded (old) document with the new document that replaced it after
 * an external edit / bake (PLAN.md sec. 2). Emits a `relink` journal event and triggers a rescan so
 * the fold carries the old document's metadata onto the new id and marks the old doc superseded
 * (see [Indexer.applyFold]).
 *
 * The commit + rescan go through [JournalCoordinator.commitAndRescan] so they are serialized against
 * every other journal write. Committing directly through the shared, non concurrency-safe
 * `SegmentWriter` here would race metadata writes and corrupt the single-writer bookkeeping.
 */
class RelinkService(
    private val coordinator: JournalCoordinator,
) {
    /**
     * Record oldDocId -> newDocId and refold. Returns the [ScanReport] of the triggered rescan so
     * callers can observe the resulting state.
     */
    suspend fun applyRelink(oldDocId: String, newDocId: String): ScanReport =
        coordinator.commitAndRescan(listOf(PendingOp.relink(oldDocId, newDocId)))
}
