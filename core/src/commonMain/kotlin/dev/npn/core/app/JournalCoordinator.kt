package dev.npn.core.app

import dev.npn.core.index.Indexer
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.ScanReport
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationUnavailableException
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.SegmentWriter
import dev.npn.core.journal.SegmentRef
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.PublishedTransaction
import dev.npn.core.journal.SnapshotCompactor
import dev.npn.core.journal.systemNowMillis
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single point of serialization for all journal writes and the live [FoldedState] they depend on.
 *
 * Why this exists: building a correct OR-set `tag-remove` needs the dots currently observed for
 * `(tag, doc)`. Those live in the folded state. If two writers raced, one could commit a
 * `tag-remove` against stale dots. This class holds the [SegmentWriter] plus the current
 * [FoldedState] behind a [Mutex] so that "read current dots -> build ops -> publish -> reduce ->
 * project" is ordered.
 *
 * Normal commits apply their frozen events directly to the persisted accumulator and bounded DB
 * projection.
 */
class JournalCoordinator(
    val writer: SegmentWriter,
    private val indexer: Indexer,
    private val accumulator: MetadataAccumulator,
    objectWriter: ContentAddressedJournalWriter? = null,
    private val compactor: SnapshotCompactor? = null,
    private val compactionThreshold: Int = DEFAULT_COMPACTION_THRESHOLD,
    private val compactionStabilityWindowMs: Long = DEFAULT_COMPACTION_STABILITY_WINDOW_MS,
) {
    private val mutex = Mutex()
    private var objectWriter = objectWriter
    private var writeBlockReason: String? = null

    private val _state = MutableStateFlow(FoldedState())
    private var state: FoldedState
        get() = _state.value
        set(value) { _state.value = value }

    /** Observable folded state; re-emits after every refresh/commit. */
    val stateFlow: StateFlow<FoldedState> = _state.asStateFlow()

    private val _settings = MutableStateFlow<Map<String, String?>>(emptyMap())

    fun settingFlow(key: String): kotlinx.coroutines.flow.Flow<String?> =
        _settings.map { it[key] }

    /**
     * Refresh the in-memory [FoldedState] from the store. Called at boot and after any external
     * rescan so that dot lookups reflect other devices' segments too. Does not touch the DB.
     */
    suspend fun refreshState(preserveLifecycleDocs: Set<String> = emptySet()) = mutex.withLock {
        try {
            state = accumulator.reconcile().state
            observeSourceHlc()
            seedSettings()
            indexer.refreshState(state, preserveLifecycleDocs)
            accumulator.markReady(state.maxHlc)
            writeBlockReason = null
        } catch (failure: Throwable) {
            blockWrites(failure)
            throw failure
        }
    }

    /** The current folded state. Safe to read for dot lookups; treat as a snapshot. */
    val currentState: FoldedState get() = state

    suspend fun ingestObject(relPath: String) = mutex.withLock {
        try {
            var events: List<JournalEvent> = emptyList()
            indexer.withFtsWriteGate {
                accumulator.withProjectionTransaction {
                    events = accumulator.ingestObject(relPath)
                    if (events.isEmpty()) return@withProjectionTransaction
                    events.forEach(state::apply)
                    observeSourceHlc()
                    indexer.refreshEvents(state, events, ftsGateHeld = true)
                }
            }
            updateSettings(events)
        } catch (_: Throwable) {
            recoverDurableProjection()
        }
    }

    /** Strictly reconcile metadata before touching the document projection. */
    suspend fun rescan(): ScanReport = mutex.withLock {
        try {
            state = accumulator.reconcile(state).state
            observeSourceHlc()
            seedSettings()
            val report = indexer.rescan(metadataState = state)
            accumulator.markReady(state.maxHlc)
            writeBlockReason = null
            report
        } catch (failure: Throwable) {
            blockWrites(failure)
            throw failure
        }
    }

    suspend fun reconcileMetadata() = mutex.withLock {
        try {
            val reconciliation = accumulator.reconcile(state)
            state = reconciliation.state
            observeSourceHlc()
            seedSettings()
            // Bounded projection when reconcile only ADDED events onto the prior state; otherwise
            // full reproject (source removed/invalidated, mutation, full refold, first load).
            if (reconciliation.incremental) {
                indexer.refreshEvents(state, reconciliation.newEvents)
            } else if (reconciliation.sourcesRead > 0) {
                indexer.refreshState(state)
            }
            accumulator.markReady(state.maxHlc)
            writeBlockReason = null
        } catch (failure: Throwable) {
            blockWrites(failure)
            throw failure
        }
    }

    /**
     * Compact old, stable storage-v2 objects into one supersession snapshot, then delete the
     * superseded files. Runs under the single-writer mutex so the snapshot write and deletions are
     * serialized against every other journal write. A no-op when compaction is unsupported, writes
     * are blocked, or the threshold/stability gate is not met.
     *
     * The snapshot ingest uses the full-refold path implicitly on the NEXT reconcile if needed;
     * here we record it directly (its events already exist in the DB, so the fold is unchanged) and
     * only delete superseded files that are covered by the applied snapshot.
     *
     * Returns the snapshot's content hash if one was published, else null.
     */
    suspend fun compact(nowMillis: Long = systemNowMillis()): String? = mutex.withLock {
        val activeCompactor = compactor ?: return@withLock null
        if (!activeCompactor.supported || writeBlockReason != null) return@withLock null
        val support = accumulator.snapshotSupport
        val (objects, snapshots) = support.compactionCandidates()
        val plan = activeCompactor.plan(
            objects = objects,
            snapshots = snapshots,
            threshold = compactionThreshold,
            nowMillis = nowMillis,
            stabilityWindowMs = compactionStabilityWindowMs,
        ) ?: return@withLock null
        withContext(NonCancellable) {
            val published = activeCompactor.publish(plan) ?: return@withContext null
            try {
                accumulator.withProjectionTransaction { support.recordLocalSnapshot(published) }
                support.deleteSupersededFiles(published.supersedes.mapNotNull { hash ->
                    plan.firstOrNull { it.contentHash == hash }?.relPath
                })
                published.contentHash
            } catch (_: Throwable) {
                recoverDurableProjection()
                null
            }
        }
    }

    /**
     * Read the current folded state under the lock, letting [block] build the ops it needs from
     * live dots, then publish and incrementally project one transaction.
     *
     * Returns true if anything was committed (non-empty op list), false for a no-op.
     */
    suspend fun commitBuilt(block: (FoldedState) -> List<PendingOp>): Boolean = mutex.withLock {
        ensureWritable()
        val ops = block(state)
        if (ops.isEmpty()) return@withLock false
        // Once the write-once segment exists, cancellation must not leave the disposable DB
        // projection stale until the next rescan or edit.
        withContext(NonCancellable) {
            val (published, written) = writeTransaction(ops)
            val path = published?.relPath ?: NpnPaths.journalSegmentPath(
                writer.deviceId, SegmentRef.plainName(written.first().seq),
            )
            val version = if (published == null) NpnPaths.STORAGE_V1 else NpnPaths.STORAGE_V2
            try {
                indexer.withFtsWriteGate {
                    accumulator.withProjectionTransaction {
                        accumulator.recordLocalSource(path, version, published?.contentHash, written)
                        written.forEach(state::apply)
                        observeSourceHlc()
                        indexer.refreshEvents(state, written, ftsGateHeld = true)
                    }
                }
                updateSettings(written)
            } catch (_: Throwable) {
                recoverDurableProjection()
            }
        }
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
        ensureWritable()
        withContext(NonCancellable) {
            val (published, written) = writeTransaction(ops)
            if (written.isNotEmpty()) {
                val path = published?.relPath ?: NpnPaths.journalSegmentPath(
                    writer.deviceId, SegmentRef.plainName(written.first().seq),
                )
                val version = if (published == null) NpnPaths.STORAGE_V1 else NpnPaths.STORAGE_V2
                accumulator.recordLocalSource(path, version, published?.contentHash, written)
                // Fold the freshly-written events into the live state up front. reconcile()'s
                // fast path returns currentState unchanged when it read no new sources (the events
                // were just recorded directly), so the projection rescan below must see them here.
                written.forEach(state::apply)
            }
            state = accumulator.reconcile(state).state
            observeSourceHlc()
            seedSettings()
            val report = indexer.rescan(metadataState = state)
            accumulator.markReady(state.maxHlc)
            report
        }
    }

    private fun seedSettings() {
        _settings.value = mapOf(ReviewMode.WIRE_KEY to state.effectiveSetting(ReviewMode.WIRE_KEY))
    }

    private fun updateSettings(events: List<JournalEvent>) {
        val changed = events.mapNotNull { (it.op as? JournalOp.SettingSet)?.key }.toSet()
        if (changed.isEmpty()) return
        _settings.value = _settings.value.toMutableMap().apply {
            changed.forEach { key -> put(key, state.effectiveSetting(key)) }
        }
    }

    private suspend fun writeTransaction(ops: List<PendingOp>): Pair<PublishedTransaction?, List<JournalEvent>> {
        val contentWriter = objectWriter ?: return null to writer.commit(ops)
        return try {
            val published = contentWriter.commit(ops)
            published to checkNotNull(published).events
        } catch (_: VerifiedPublicationUnavailableException) {
            objectWriter = null
            null to writer.commit(ops)
        }
    }

    private fun ensureWritable() {
        check(writeBlockReason == null) { "Metadata writes are disabled: $writeBlockReason" }
    }

    private fun blockWrites(failure: Throwable) {
        writeBlockReason = failure.message ?: failure::class.simpleName ?: "metadata reconciliation failed"
    }

    private suspend fun observeSourceHlc() {
        writer.observeHlc(state.maxHlc)
        val contentWriter = objectWriter ?: return
        contentWriter.observeHlc(state.maxHlc)
        val (physical, counter) = contentWriter.watermark
        accumulator.persistAllocator(physical, counter)
    }

    private suspend fun recoverDurableProjection() {
        try {
            state = accumulator.reconcile().state
            observeSourceHlc()
            indexer.refreshState(state)
            accumulator.markReady(state.maxHlc)
            seedSettings()
            writeBlockReason = null
        } catch (recoveryFailure: Throwable) {
            blockWrites(recoveryFailure)
            throw recoveryFailure
        }
    }

    companion object {
        /** Compact once the live (non-superseded) content-addressed object count exceeds this. */
        const val DEFAULT_COMPACTION_THRESHOLD = 64

        /** Only compact objects whose mtime is older than this, to avoid racing an in-flight sync. */
        const val DEFAULT_COMPACTION_STABILITY_WINDOW_MS = 24L * 60L * 60L * 1000L
    }
}
