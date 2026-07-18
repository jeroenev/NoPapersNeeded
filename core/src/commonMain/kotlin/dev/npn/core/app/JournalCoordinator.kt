package dev.npn.core.app

import dev.npn.core.index.Indexer
import dev.npn.core.index.ScanReport
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.JournalIndex
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SegmentWriter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Single point of serialization for all journal writes and the live [FoldedState] they depend on.
 *
 * Why this exists: building a correct OR-set `tag-remove` needs the dots currently observed for
 * `(tag, doc)`. Those live in the folded state. If two writers raced, one could commit a
 * `tag-remove` against stale dots. This class holds the [SegmentWriter] plus the current
 * [FoldedState] behind a [Mutex] so that "read current dots -> build ops -> commit -> refold ->
 * apply to DB" is atomic.
 *
 * [commitAndApply] reuses the indexer's existing fold-apply path ([Indexer.refreshFold]) rather
 * than duplicating the fold-to-DB logic. After committing a new segment the fold is refreshed and
 * the DB projection updated in the same critical section, so [currentState] and the DB never drift
 * apart between two consecutive metadata writes.
 */
class JournalCoordinator(
    val writer: SegmentWriter,
    private val journalIndex: JournalIndex,
    private val indexer: Indexer,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(FoldedState())
    private var state: FoldedState
        get() = _state.value
        set(value) { _state.value = value }

    /** Observable folded state; re-emits after every refresh/commit. */
    val stateFlow: StateFlow<FoldedState> = _state.asStateFlow()

    /**
     * Refresh the in-memory [FoldedState] from the store. Called at boot and after any external
     * rescan so that dot lookups reflect other devices' segments too. Does not touch the DB.
     */
    suspend fun refreshState() = mutex.withLock {
        state = journalIndex.refresh().state
        writer.observeHlc(state.maxHlc)
    }

    /** The current folded state. Safe to read for dot lookups; treat as a snapshot. */
    val currentState: FoldedState get() = state

    /**
     * Read the current folded state under the lock, letting [block] build the ops it needs from
     * live dots, then commit them as one segment and refold+apply to the DB. All atomic.
     *
     * Returns true if anything was committed (non-empty op list), false for a no-op.
     */
    suspend fun commitBuilt(block: (FoldedState) -> List<PendingOp>): Boolean = mutex.withLock {
        val ops = block(state)
        if (ops.isEmpty()) return@withLock false
        writer.commit(ops)
        // Refold from the store (picks up the just-written segment) and apply to the DB.
        state = journalIndex.refresh().state
        writer.observeHlc(state.maxHlc)
        indexer.refreshFold()
        true
    }

    /** Convenience for op lists that do not depend on current dots. */
    suspend fun commitAndApply(ops: List<PendingOp>): Boolean = commitBuilt { ops }

    /**
     * Commit [ops] as one segment and run a full [Indexer.rescan] inside the same critical section.
     *
     * Unlike [commitBuilt] (which only refolds), this is for ops such as `relink` whose fold effect
     * needs a full rescan to carry metadata onto the new id and mark the superseded doc. Serializing
     * this against every other write preserves the single-writer invariant: the shared, non
     * concurrency-safe [SegmentWriter] is never touched by two callers at once.
     *
     * Returns the resulting [ScanReport]. Refreshes [currentState] from the store after the rescan.
     */
    suspend fun commitAndRescan(ops: List<PendingOp>): ScanReport = mutex.withLock {
        writer.commit(ops)
        val report = indexer.rescan()
        state = journalIndex.refresh().state
        writer.observeHlc(state.maxHlc)
        report
    }
}
