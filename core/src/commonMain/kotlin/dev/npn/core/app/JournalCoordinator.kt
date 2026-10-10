package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.index.ImportJournal
import dev.npn.core.index.Indexer
import dev.npn.core.index.MetadataAccumulator
import dev.npn.core.index.OpenReconciliation
import dev.npn.core.index.Resurrected
import dev.npn.core.index.ScanReport
import dev.npn.core.index.SnapshotSupport
import dev.npn.core.journal.ContentAddressedJournalWriter
import dev.npn.core.journal.EpochClock
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.PendingOp
import dev.npn.core.journal.PublishedTransaction
import dev.npn.core.journal.SnapshotCompactor
import dev.npn.core.journal.SystemEpochClock
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.util.NpnLog
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The library-wide synced settings a reader can observe, kept as a snapshot of their effective
 * values rather than re-derived per read. Every setting the journal holds is here, so a new synced
 * setting needs nothing but its key.
 *
 * These are `setting-set` events in the journal, so their effective values come out the same from a
 * settings-only fold as from the whole state — which is what lets [JournalCoordinator.refreshState]
 * seed them at open without replaying the journal (see [MetadataAccumulator.loadStateOf]).
 */
private class SyncedSettings {
    /** Every key ever written, null for a cleared one (see [FoldedState.settings]). */
    private val values = MutableStateFlow<Map<String, String?>>(emptyMap())

    /** [key]'s value as it changes; a write to any other setting does not re-emit it. */
    fun flow(key: String): Flow<String?> = values.map { it[key] }.distinctUntilChanged()

    /** Every setting, cleared ones as null. */
    fun all(): Flow<Map<String, String?>> = values

    /** The current value of a key, without touching the fold. */
    fun current(key: String): String? = values.value[key]

    /** Whether [key] was ever written, a cleared value included. */
    fun written(key: String): Boolean = key in values.value

    /** Replace every setting from [source]. */
    fun seed(source: FoldedState) {
        values.value = source.settings()
    }

    /** Refresh only the keys [events] actually wrote. */
    fun update(
        current: FoldedState,
        events: List<JournalEvent>,
    ) {
        val changed = events.mapNotNull { (it.op as? JournalOp.SettingSet)?.key }.toSet()
        if (changed.isEmpty()) return
        values.value =
            values.value.toMutableMap().apply {
                changed.forEach { key -> put(key, current.effectiveSetting(key)) }
            }
    }
}

/**
 * A change to one document's lifecycle as [JournalCoordinator.changeLifecycle] carries it out under
 * the writer lock: [move] its file (nothing, for a change that moves none), [optimisticWrite] its
 * row as the change leaves it, then publish [ops]. [undoMove] puts the file back when nothing was
 * published.
 */
class LifecycleChange(
    val ops: List<PendingOp>,
    val optimisticWrite: suspend () -> Unit,
    val move: suspend () -> Unit = {},
    val undoMove: suspend () -> Unit = {},
) {
    init {
        require(ops.isNotEmpty()) { "A lifecycle change publishes at least one event" }
    }
}

/**
 * Single point of serialization for all journal writes and the live [FoldedState] they depend on.
 *
 * Why this exists: building a correct OR-set `tag-remove` needs the dots currently observed for
 * `(tag, doc)`. Those live in the folded state. If two writers raced, one could commit a
 * `tag-remove` against stale dots. This class holds the [ContentAddressedJournalWriter] plus the
 * current [FoldedState] behind a [Mutex] so that "read current dots -> build ops -> publish ->
 * reduce -> project" is ordered.
 *
 * Every transaction is published as one storage-v2 object. There is no other format to fall back
 * to: a filesystem that cannot publish without replacement fails the write, and the session refuses
 * to open such a library in the first place (see [LibrarySessionImpl.openSession]). Legacy v1
 * segments are still read and folded.
 *
 * Normal commits apply their frozen events directly to the persisted accumulator and bounded DB
 * projection.
 *
 * The [FoldedState] is folded on DEMAND, not at boot (see [folded]). Folding it replays every
 * event the library has ever recorded, and only writers and rescans need it — readers go to the
 * Room projection, which [refreshState] can usually reuse wholesale. Anything here that needs the
 * state must therefore go through [foldedState] rather than assume it already exists.
 *
 * This is the ONLY sanctioned journal write path (see AGENTS.md). The invariant is enforced at
 * the type level rather than by convention: [ContentAddressedJournalWriter] and the primary
 * constructor are `internal` to `core` and the held [writer] is private, so no caller outside
 * `core` can obtain the raw writer and skip the mutex, the live [FoldedState] refresh, or the DB
 * projection. Expose new capabilities as named members here rather than widening [writer].
 */
@Suppress("TooManyFunctions")
class JournalCoordinator internal constructor(
    private val writer: ContentAddressedJournalWriter,
    private val indexer: Indexer,
    private val accumulator: MetadataAccumulator,
    private val compactor: SnapshotCompactor? = null,
    /** The source bookkeeping [compact] plans from and records into; given with a [compactor]. */
    private val snapshots: SnapshotSupport? = null,
    private val compactionThreshold: Int = DEFAULT_COMPACTION_THRESHOLD,
    private val compactionStabilityWindowMs: Long = DEFAULT_COMPACTION_STABILITY_WINDOW_MS,
    /** Where folds and writes run, whatever dispatcher the caller is on. */
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** How long ago a document was hidden, when a rescan finds its file back (see [recordRestores]). */
    private val clock: EpochClock = SystemEpochClock,
    /** Where a rescan's failure to record a restore goes; the rescan itself carries on. */
    private val log: NpnLog = NpnLog.None,
) {
    private val mutex = Mutex()
    private var writeBlockReason: String? = null
    private var closed = false

    /**
     * Run [block] on [dispatcher] under the single-writer [mutex], refusing once [close] has run.
     * Every fold and journal write goes through here, so [close] doubles as the barrier the session
     * needs before its database is closed, and every entry point is main-safe: a facet called from
     * the UI thread never folds, encodes or hashes there.
     */
    private suspend inline fun <T> locked(crossinline block: suspend () -> T): T =
        withContext(dispatcher) {
            mutex.withLock {
                check(!closed) { "Library session is closed" }
                block()
            }
        }

    /**
     * Wait for the write or fold in progress, if any, then refuse every later one. A caller that
     * started one outside the session's own jobs (a rescan when the app returns to the foreground)
     * would otherwise still be writing to the database when the session closes it; the native SQLite
     * driver crashes rather than throwing.
     */
    suspend fun close() = mutex.withLock { closed = true }

    /**
     * The folded journal state, or null when this session has not needed it yet.
     *
     * Materializing it means replaying every event ever recorded for this library, so it is folded
     * on demand by [foldedState] rather than at boot. Nothing on the READ path wants it — readers
     * go to the Room projection — so a session that only browses may never fold at all. Writes
     * (which need live OR-set dots) and rescans (which project metadata onto document rows) do,
     * and they pay for it at the point of use.
     */
    private var folded: FoldedState? = null

    /**
     * Set, with [folded] dropped, when a pass failed or was cut short, or when rebuilding the
     * projection after a failure failed: that pass may have recorded sources it never folded or
     * projected. A fold built while it is set is the store's whole, but the projection may lack them,
     * so the next reconcile or ingest refolds and reprojects in full ([recoverDurableProjection])
     * rather than build on what it had. A rescan, which projects every document, settles it too.
     */
    private var refoldPending = false

    private val syncedSettings = SyncedSettings()

    fun settingFlow(key: String): Flow<String?> = syncedSettings.flow(key)

    /** Every synced setting the journal holds, a cleared one as null; for keys a reader enumerates. */
    fun settings(): Flow<Map<String, String?>> = syncedSettings.all()

    /** The current value of the synced setting [key], without touching the fold. */
    fun setting(key: String): String? = syncedSettings.current(key)

    /**
     * Write each of [groups] whose keys the journal has never held, a cleared one included: a
     * one-time adoption of what this device had, which must not overwrite what any replica already
     * decided. A group is one setting kept under one or more keys, adopted whole or not at all: a
     * journal holding any of its keys takes none of them. Like [seedFieldRegistryIfNeeded], the
     * in-memory check runs first, so only an open that has something to adopt pays for the fold.
     *
     * Returns whether the journal now settles every group, false when writes are blocked and nothing
     * was adopted, so a caller keeps its copy for the next attempt.
     */
    suspend fun adoptSettings(groups: List<Map<String, String>>): Boolean {
        if (groups.all { group -> group.keys.any(syncedSettings::written) }) return true
        if (writeBlockReason != null) return false
        commitBuilt { state ->
            val held = state.settings()
            groups
                .filter { group -> group.keys.none { it in held } }
                .flatMap { group -> group.map { (key, value) -> PendingOp.settingSet(key, value) } }
        }
        return true
    }

    /**
     * Every document the journal knows that is deleted, or else not superseded, mapped to when it was
     * deleted for good or trashed, or to null while it is neither ([FoldedState.trashTimes]). Read from
     * the live fold, which the rescan before a trash purge has built.
     */
    suspend fun journalTrashTimes(): Map<String, Long?> = locked { foldedState().trashTimes() }

    /**
     * Write the well-known field definitions once per library, and bring a library seeded at an
     * older [version] up to it once ([FieldKeys.upgradeSeeded]). Guarded by the synced
     * [FieldKeys.SEEDED_SETTING] marker rather than by "are there any definitions", so a user who
     * deletes every default field does not get them back when another device opens the library.
     *
     * The cheap marker check runs first: [commitBuilt] materializes the whole fold, and paying
     * that on every open is exactly what [refreshState]'s projection reuse avoids. Only a library
     * that has not been seeded at [version] yet (once, ever) reaches the fold.
     */
    suspend fun seedFieldRegistryIfNeeded(
        defs: List<FieldDef>,
        version: Int = FieldKeys.SEED_VERSION,
    ) {
        if (seedVersion(syncedSettings.current(FieldKeys.SEEDED_SETTING)) >= version) return
        if (writeBlockReason != null) return
        commitBuilt { state ->
            val seeded = state.effectiveSetting(FieldKeys.SEEDED_SETTING)
            val defOps =
                when {
                    seedVersion(seeded) >= version -> return@commitBuilt emptyList()
                    seeded == null -> defs
                    else -> FieldKeys.upgradeSeeded(state.effectiveFieldDefs(), defs)
                }.map { PendingOp.fieldDefSet(it) }
            defOps + PendingOp.settingSet(FieldKeys.SEEDED_SETTING, version.toString())
        }
    }

    /** The seed version a [FieldKeys.SEEDED_SETTING] value records: 0 when absent, 1 for the first marker. */
    private fun seedVersion(marker: String?): Int = if (marker == null) 0 else marker.toIntOrNull() ?: 1

    /** The folded state, replaying it from the accumulator the first time it is actually needed. */
    private suspend fun foldedState(): FoldedState = folded ?: accumulator.loadState().also { folded = it }

    /**
     * Bring this session in step with the store at open time.
     *
     * The usual outcome is that nothing changed since the last close, in which case the stored Room
     * projection is already the fold of exactly the sources on disk and there is nothing to do but
     * seed the write clock and the settings a reader needs. Only a moved journal (or an unusable
     * projection) costs a refold and a whole-DB reprojection here.
     */
    suspend fun refreshState() =
        locked {
            latching {
                when (val outcome = accumulator.reconcileOnOpen()) {
                    is OpenReconciliation.ProjectionCurrent -> {
                        folded = null
                        observeSourceHlc(outcome.maxHlc)
                        syncedSettings.seed(accumulator.loadStateOf(JournalOp.SettingSet.WIRE))
                        writeBlockReason = null
                    }

                    is OpenReconciliation.Refolded -> {
                        adopt(outcome.state) { indexer.refreshState(outcome.state) }
                    }
                }
            }
        }

    suspend fun ingestObject(relPath: String) =
        locked {
            // The full path reads this object too.
            if (refoldPending) return@locked latching { recoverDurableProjection() }
            withDurableProjectionRecovery {
                // Fold before opening the transaction: loadState reads the events as they stand
                // BEFORE this object is ingested, which is exactly the base its events apply onto.
                val current = foldedState()
                val staged = accumulator.readObject(relPath)
                var events: List<JournalEvent> = emptyList()
                if (staged != null) {
                    indexer.projection {
                        events = accumulator.ingestObject(staged)
                        if (events.isEmpty()) return@projection
                        events.forEach(current::apply)
                        observeSourceHlc(current.maxHlc)
                        indexer.refreshEvents(current, events)
                    }
                }
                syncedSettings.update(current, events)
                // The projection now matches the enlarged source set; record that so the next open
                // can reuse it instead of refolding.
                accumulator.markReady(current.maxHlc)
            }
        }

    /** Strictly reconcile metadata before touching the document projection. */
    suspend fun rescan(): ScanReport = locked { latching { rescanFrom(foldedState()) } }

    suspend fun reconcileMetadata() =
        locked {
            latching {
                if (refoldPending) {
                    recoverDurableProjection()
                    return@locked
                }
                val reconciliation = accumulator.reconcile(folded)
                val current = reconciliation.state
                if (current == null) {
                    // Nothing on disk changed and this session has never needed the fold. There is
                    // nothing to project, and replaying the journal just to prove that is what made
                    // the periodic tick as expensive as a boot.
                    writeBlockReason = null
                    return@locked
                }
                adopt(current) {
                    // Bounded projection when reconcile only ADDED events onto the prior state;
                    // otherwise full reproject (source removed/invalidated, mutation, full refold,
                    // first load).
                    if (reconciliation.incremental) {
                        indexer.refreshEvents(current, reconciliation.newEvents)
                    } else if (reconciliation.sourcesRead > 0) {
                        indexer.refreshState(current)
                    }
                }
            }
        }

    /**
     * Compact old, stable storage-v2 objects into one supersession snapshot, then delete the
     * superseded files. Runs under the single-writer mutex so the snapshot write and deletions are
     * serialized against every other journal write. A no-op without a [compactor], while writes are
     * blocked, or when the threshold/stability gate is not met. That gate is decided from the source
     * table alone, so the usual tick reads no event and no file; only the objects a plan picks are
     * checked on disk and have their events loaded.
     *
     * The snapshot ingest uses the full-refold path implicitly on the NEXT reconcile if needed;
     * here we record it directly (its events already exist in the DB, so the fold is unchanged) and
     * only delete superseded files that are covered by the applied snapshot.
     *
     * Returns the snapshot's content hash if one was published, else null.
     */
    suspend fun compact(nowMillis: Long = clock.nowMillis()): String? =
        locked {
            val activeCompactor = compactor ?: return@locked null
            val support = checkNotNull(snapshots) { "A coordinator that compacts needs its SnapshotSupport" }
            if (writeBlockReason != null) return@locked null
            val (objects, snapshots) = support.compactionSources()
            val chosen =
                activeCompactor.plan(
                    objects = objects,
                    snapshots = snapshots,
                    threshold = compactionThreshold,
                    nowMillis = nowMillis,
                    stabilityWindowMs = compactionStabilityWindowMs,
                ) ?: return@locked null
            val plan = support.candidates(chosen) ?: return@locked null
            withContext(NonCancellable) {
                val published = activeCompactor.publish(plan) ?: return@withContext null
                withDurableProjectionRecovery {
                    support.recordLocalSnapshot(published)
                    support.deleteSupersededFiles(
                        published.supersedes.mapNotNull { hash ->
                            plan.firstOrNull { it.contentHash == hash }?.relPath
                        },
                    )
                    published.contentHash
                }
            }
        }

    /**
     * Read the current folded state under the lock, letting [block] build the ops it needs from
     * live dots, then publish and incrementally project one transaction.
     *
     * Returns true if anything was committed (non-empty op list), false for a no-op.
     */
    suspend fun commitBuilt(block: (FoldedState) -> List<PendingOp>): Boolean =
        locked {
            ensureWritable()
            val current = foldedState()
            val ops = block(current)
            if (ops.isEmpty()) return@locked false
            commitOnto(current, ops)
            true
        }

    /**
     * Publish non-empty [ops] and project them incrementally onto [current], the live fold they
     * were built on. Once the write-once object exists, cancellation must not leave the disposable
     * DB projection stale until the next rescan or edit, so the whole of it is [NonCancellable].
     * Callers hold the mutex.
     */
    private suspend fun commitOnto(
        current: FoldedState,
        ops: List<PendingOp>,
    ) = withContext(NonCancellable) { project(current, writeTransaction(ops)) }

    /** Record [published], a transaction this session just wrote, and project it onto [current]. */
    private suspend fun project(
        current: FoldedState,
        published: PublishedTransaction,
        preserveLifecycleDocs: Set<String> = emptySet(),
    ) = withDurableProjectionRecovery {
        val local = accumulator.localSource(published)
        indexer.projection {
            recordWritten(current, local)
            indexer.refreshEvents(current, published.events, preserveLifecycleDocs = preserveLifecycleDocs)
        }
        syncedSettings.update(current, published.events)
        // This commit added a source and projected it incrementally, so the projection matches
        // the new source set. Recording that is what lets the next open skip the refold; without
        // it, any edit in one session would cost a full replay in the next.
        accumulator.markReady(current.maxHlc)
    }

    /**
     * [project] [published] for a caller that undoes what it did when the call fails: a moved PDF,
     * an imported file. The journal holds the change now, so it stands, and the caller must not
     * undo it. A failure to project it has already been dealt with as for any commit (writes
     * latched, the projection rebuilt from the store), so it is logged here rather than thrown.
     */
    private suspend fun projectStanding(
        current: FoldedState,
        published: PublishedTransaction,
        preserveLifecycleDocs: Set<String> = emptySet(),
    ) {
        log.runLogged("Projecting the published ${published.relPath}") {
            project(current, published, preserveLifecycleDocs)
        }
    }

    suspend fun commitAndApply(ops: List<PendingOp>): Boolean = commitBuilt { ops }

    /**
     * Throw now if a commit would be refused. For a caller that changes the filesystem before it
     * commits, outside the lock (an in-place bake, before its relink), so a refused commit does not
     * strand a rewritten PDF with no journal record of it; or that pays for work only a commit can
     * keep. A file move that can run under the lock goes through [changeLifecycle] instead.
     */
    suspend fun checkWritable() = locked { ensureWritable() }

    /**
     * Run [block] under the single-writer mutex: for a change to which file holds which hash that
     * does not go through the journal, an import placing its file and row. That serializes it
     * against other imports and against every rescan, whose listing and row writes it would
     * otherwise interleave with. [block] reads and writes the journal through the [ImportJournal] it
     * is handed, under the same lock: an import reads the relinks and records a returning
     * document's return in the step that made its row live, and a delete for good removes the bytes
     * only while the journal still has the document deleted. It must not call another member here,
     * as the lock is not reentrant.
     */
    suspend fun <T> serialized(block: suspend (ImportJournal) -> T): T = locked { block(lockedJournal) }

    /** The live fold, for a [serialized] block, which holds the lock it is read and written under. */
    private val lockedJournal =
        object : ImportJournal {
            override suspend fun successorOf(docId: String): String = foldedState().resolveDocId(docId)

            override suspend fun isDeleted(docId: String): Boolean = foldedState().isDeleted(docId)

            override suspend fun restoreReturned(
                docId: String,
                relPath: String,
            ): Boolean {
                val current = foldedState()
                val ops = current.restoreOps(docId, relPath)
                if (ops.isEmpty()) return false
                ensureWritable()
                withContext(NonCancellable) { projectStanding(current, writeTransaction(ops)) }
                return true
            }
        }

    /**
     * Change [docId]'s lifecycle (trash, restore, delete) in one step under the single-writer mutex:
     * reading its row, checking and moving its file, writing the row and publishing the events. A
     * rescan, an import or a storage-path move that holds the mutex meanwhile cannot land between
     * them and leave the change acting on a row or a file that is no longer so.
     *
     * [plan] runs once writes are known to be allowed, with the document's row as it stands under
     * the mutex (null without one), and returns the [LifecycleChange] to carry out, or null for
     * nothing. From its [LifecycleChange.move] on, the change runs to its end even when the caller
     * goes away: cut short, it could leave the file moved with no event, or the event published
     * with the file moved back.
     *
     * Ordering guarantee: [LifecycleChange.optimisticWrite] (a trashed/deleted flag or a restored
     * path, so the UI reacts before the journal catches up) runs right before the publish. Every
     * concurrent fold (periodic [reconcileMetadata], [rescan], [refreshState]) also holds this mutex,
     * so none can clobber the row before its events are folded, and the committing fold keeps the
     * row's lifecycle as written (preserveLifecycleDocs in [Indexer.refreshEvents]) rather than
     * re-deriving it from a journal state that might not carry it yet.
     *
     * Throws only when nothing was published, and then, still under the mutex, puts the row back as
     * it was and undoes the move ([LifecycleChange.undoMove]), so row and file agree; a failure of
     * either is logged, as the commit's own failure is the one the caller needs. Once the transaction
     * is published the change stands ([projectStanding]).
     */
    suspend fun changeLifecycle(
        docId: String,
        plan: suspend (DocumentEntity?) -> LifecycleChange?,
    ): Boolean =
        locked {
            ensureWritable()
            val current = foldedState()
            val docs = setOf(docId)
            val before = indexer.rowsOf(docs)
            val change = plan(before.singleOrNull()) ?: return@locked false
            withContext(NonCancellable) {
                var moved = false
                var published: PublishedTransaction? = null
                try {
                    change.move()
                    moved = true
                    change.optimisticWrite()
                    published = writeTransaction(change.ops)
                } finally {
                    if (published == null && moved) {
                        log.runLogged("Putting back the row of $docId") { indexer.putBack(docs, before) }
                        log.runLogged("Moving the file of $docId back") { change.undoMove() }
                    }
                }
                projectStanding(current, checkNotNull(published), preserveLifecycleDocs = docs)
            }
            true
        }

    /**
     * Commit [ops] as one transaction and run a full [Indexer.rescan] inside the same critical
     * section.
     *
     * Unlike [commitBuilt] (which only refolds), this is for ops such as `relink` whose fold effect
     * needs a full rescan to carry metadata onto the new id and mark the superseded doc. Serializing
     * this against every other write keeps the write, the fold it is built on and the rescan that
     * projects it in one order.
     *
     * Returns the resulting [ScanReport]. Refreshes the folded state from the store after the
     * rescan.
     */
    suspend fun commitAndRescan(ops: List<PendingOp>): ScanReport =
        locked {
            ensureWritable()
            val current = foldedState()
            withContext(NonCancellable) {
                // Fold the freshly-written events into the live state up front. reconcile()'s fast
                // path returns the state unchanged when it read no new sources (the events were just
                // recorded directly), so the projection rescan must see them here.
                if (ops.isNotEmpty()) recordProjected(current, ops)
                rescanFrom(current)
            }
        }

    /**
     * Reconcile the journal onto [current], adopt the result and rescan the library against it.
     * Called under the coordinator mutex, which the external-move and restore callbacks rely on too.
     */
    private suspend fun rescanFrom(current: FoldedState): ScanReport {
        val reconciled = accumulator.reconcile(current).state ?: current
        val report =
            adopt(reconciled) {
                indexer.rescan(
                    metadataState = reconciled,
                    onExternalMoves = { ops -> withContext(NonCancellable) { recordProjected(reconciled, ops) } },
                    onResurrected = { resurrected -> recordRestores(reconciled, resurrected) },
                )
            }
        // Every document projected, from a fold built since any failed pass dropped the last one.
        refoldPending = false
        return report
    }

    /** Publish [ops] and fold them into [state], for a pass that projects what they say itself. */
    private suspend fun recordProjected(
        state: FoldedState,
        ops: List<PendingOp>,
    ) {
        recordWritten(state, accumulator.localSource(writeTransaction(ops)))
    }

    /**
     * Record as a restore the rescan's preference for a file that is back in the library, so the next
     * fold that is not a rescan does not hide the document again (and every replica shows it).
     *
     * Only for a document hidden at least [RESTORE_SETTLE_MS] ago. Sooner, a file still in place may
     * be one a sync tool has not taken away yet: it delivers another device's trash or delete event
     * before the file move that went with it, and writing a restore then would undo that device's
     * decision and leave the document pointing at a file about to disappear. Such a file is shown
     * for this scan only, as before, and restored for good by a rescan once the window has passed.
     *
     * A failure to publish is logged, not thrown: the scan has projected what it found, and the next
     * rescan finds the same return and tries again. Thrown, it would fail the scan and block every
     * write over one journal object.
     */
    private suspend fun recordRestores(
        state: FoldedState,
        resurrected: List<Resurrected>,
    ) {
        val settled = clock.nowMillis() - RESTORE_SETTLE_MS
        val returned = resurrected.filter { it.hiddenAtMillis <= settled }
        val ops = returned.flatMap { state.restoreOps(it.docId, it.relPath) }
        if (ops.isEmpty()) return
        log.runLogged("Recording the return of ${returned.joinToString { it.relPath }}") {
            withContext(NonCancellable) { recordProjected(state, ops) }
        }
    }

    /**
     * Publish a non-empty [ops] as one storage-v2 object. Any failure fails this write and leaves
     * the next one to try again the same way: a [dev.npn.core.fs.VerifiedPublicationFailedException]
     * is transient, and a [dev.npn.core.fs.VerifiedPublicationUnavailableException] means the library
     * should never have opened (the open-time check publishes through the same primitive).
     */
    private suspend fun writeTransaction(ops: List<PendingOp>): PublishedTransaction = checkNotNull(writer.commit(ops))

    private fun ensureWritable() {
        check(writeBlockReason == null) { "Metadata writes are disabled: $writeBlockReason" }
    }

    private fun blockWrites(failure: Throwable) {
        writeBlockReason = failure.reason()
    }

    /**
     * Run a fold or reconcile pass, refusing later writes if it fails: a failed pass can leave the
     * live fold out of step with the journal, and building OR-set ops on it would be wrong.
     *
     * Cancellation is not such a failure. A rescan whose caller left the screen says nothing about
     * the journal, so it blocks nothing.
     *
     * Either way the pass may have stopped between recording a source and folding or projecting it,
     * so the live fold is dropped, and the projection rebuilt in full when next reconciled
     * ([refoldPending]).
     */
    private inline fun <T> latching(pass: () -> T): T =
        try {
            pass()
        } catch (cancelled: CancellationException) {
            dropFold()
            throw cancelled
        } catch (failure: Throwable) {
            blockWrites(failure)
            dropFold()
            throw failure
        }

    /** Drop the live fold and owe a full refold and reprojection ([refoldPending]). */
    private fun dropFold() {
        folded = null
        refoldPending = true
    }

    /**
     * Make [state], freshly reconciled from the store, this session's live fold: seed the write
     * clock and the synced settings from it, let [project] bring the index in step with it, then
     * record the projection as ready for the next open and allow writes again. A new entry point
     * that adopts a state goes through here, so none of these steps can be forgotten.
     */
    private suspend inline fun <T> adopt(
        state: FoldedState,
        project: () -> T,
    ): T {
        folded = state
        observeSourceHlc(state.maxHlc)
        syncedSettings.seed(state)
        val result = project()
        // Read after projecting: a rescan's own writes (external moves, restores) raise it.
        accumulator.markReady(state.maxHlc)
        writeBlockReason = null
        return result
    }

    /**
     * Record [local], a transaction this session just published, as an applied source and fold its
     * events into [state], so the store and the live fold both hold the write. Projecting it is the
     * caller's.
     */
    private suspend fun recordWritten(
        state: FoldedState,
        local: MetadataAccumulator.LocalSource,
    ) {
        accumulator.recordLocalSource(local)
        local.written.forEach(state::apply)
        observeSourceHlc(state.maxHlc)
    }

    /**
     * Seed the write clock past [maxHlc] and persist its watermark. Takes the watermark explicitly
     * rather than reading it off the folded state, so the open path can pass the value its stored
     * projection recorded without folding anything.
     */
    private suspend fun observeSourceHlc(maxHlc: Hlc) {
        writer.observeHlc(maxHlc)
        val (physical, counter) = writer.watermark
        accumulator.persistAllocator(physical, counter)
    }

    /**
     * Run [attempt] — a bounded projection of an ALREADY-durable write/publish — and, on any
     * failure, keep the disposable in-memory state + DB projection from diverging from the journal.
     *
     * On failure the ORIGINAL exception is preserved (its message latches [writeBlockReason]) while
     * we try to rebuild the projection from the durable store via [recoverDurableProjection]:
     *  - recovery succeeds -> the projection is consistent again and writes are re-enabled, but the
     *    original failure is still rethrown so the caller learns the optimistic projection failed;
     *  - recovery fails -> that failure also latches [writeBlockReason] (never escapes unlatched)
     *    and is attached as suppressed to the rethrown original, and the live fold is dropped
     *    for the next reconcile or ingest to rebuild in full ([refoldPending]).
     *
     * This mirrors the latch-then-rethrow contract of [refreshState]/[rescan]/[reconcileMetadata]
     * so every entry point behaves uniformly, cancellation included (see [latching]).
     */
    private suspend fun <T> withDurableProjectionRecovery(attempt: suspend () -> T): T =
        // Cancellation is not a failure, so nothing is latched for it. A rolled-back ingest can
        // leave its events in the live fold, which is harmless: the object is ingested again and
        // folding is idempotent.
        runCatchingCancellable { attempt() }.getOrElse { failure ->
            blockWrites(failure)
            runCatchingCancellable { recoverDurableProjection() }.onFailure { recoveryFailure ->
                blockWrites(recoveryFailure)
                dropFold()
                failure.addSuppressed(recoveryFailure)
            }
            throw failure
        }

    /**
     * Rebuild the in-memory state + DB projection from the durable store; clears the write block and
     * settles [refoldPending].
     */
    private suspend fun recoverDurableProjection() {
        // Recovery must reproject unconditionally, so it always needs a materialized fold even
        // when the reconcile itself read nothing.
        val current = accumulator.reconcile().state ?: accumulator.loadState()
        adopt(current) { indexer.refreshState(current) }
        refoldPending = false
    }

    companion object {
        /** Compact once the live (non-superseded) content-addressed object count exceeds this. */
        const val DEFAULT_COMPACTION_THRESHOLD = 64

        /** Only compact objects whose mtime is older than this, to avoid racing an in-flight sync. */
        const val DEFAULT_COMPACTION_STABILITY_WINDOW_MS = 24L * 60L * 60L * 1000L

        /** How long a document must have been hidden before a rescan records its file's return. */
        const val RESTORE_SETTLE_MS = 24L * 60L * 60L * 1000L
    }
}
