package dev.npn.core.index

import dev.npn.core.db.MetadataEventEntity
import dev.npn.core.db.MetadataProjectionStateEntity
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.MetadataTransactionActorEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.ProjectionStatus
import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.CanonicalJournalEvent
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalPath
import dev.npn.core.journal.JournalPaths
import dev.npn.core.journal.JournalSourceRead
import dev.npn.core.journal.PublishedTransaction
import dev.npn.core.journal.SnapshotV2Reader
import dev.npn.core.journal.StorageV2Reader

/** Why a library refuses metadata writes, as a caller can act on it. */
enum class WriteBlock {
    /**
     * The journal holds what only a newer build reads: an op, an event or snapshot version, or an
     * object store this build does not know. Updating the app is the way out.
     */
    NEWER_FORMAT,

    /**
     * A journal source does not read as valid, shrank, or disappeared without a replacement: damaged,
     * or still arriving through a sync. A later pass that reads the journal whole lifts it.
     */
    JOURNAL_DAMAGED,

    /** A fold, reconcile or projection pass failed for another reason, such as storage or the index. */
    RECONCILE_FAILED,
}

/**
 * Thrown by a pass that finds the journal cannot be folded, and by every write refused while that
 * stands; [block] says why. The message is the technical cause, in English, for the log and support.
 */
class MetadataWriteBlocked(
    val block: WriteBlock,
    message: String,
) : IllegalStateException(message)

/**
 * Outcome of a [MetadataAccumulator.reconcile] pass.
 *
 * @property state the folded state to project (either the caller's in-memory state with
 *   [newEvents] already applied, or a full refold from the DB). Null only when this pass read
 *   nothing AND the caller had no materialized state to begin with: there is then nothing to
 *   project, so replaying the journal purely to hand back a value would be wasted work.
 * @property sourcesRead number of source files that were (re)ingested this pass.
 * @property newEvents the journal events newly applied to [state] this pass, in the order they
 *   were ingested. Only meaningful when [incremental] is true.
 * @property incremental true when [state] is the caller's prior state with only [newEvents]
 *   added (no source removed/invalidated, no event dropped, no full refold), so callers may
 *   project just [newEvents] via a bounded reprojection. When false the caller must fully
 *   reproject: [state] was rebuilt from the DB and may differ arbitrarily from its prior value.
 */
internal data class MetadataReconciliation(
    val state: FoldedState?,
    val sourcesRead: Int,
    val newEvents: List<JournalEvent> = emptyList(),
    val incremental: Boolean = false,
)

/**
 * Outcome of the open-time reconcile ([MetadataAccumulator.reconcileOnOpen]).
 *
 * Opening a library asks a different question from every other reconcile. The running paths ask
 * "what changed, and what must I fold in"; the open path can also ask "did anything change at
 * all", because the answer is usually no and the stored projection is then already the fold of
 * exactly the sources on disk. That case is worth a distinct outcome: the projection is a pure
 * function of the applied source set, so an unchanged source set means neither the whole-journal
 * refold nor the whole-DB reprojection can produce a different result, and both can be skipped.
 */
internal sealed interface OpenReconciliation {
    /**
     * The applied source set is byte-identical to the one the stored, ready projection was built
     * from. Nothing to refold and nothing to reproject; [maxHlc] is the watermark that projection
     * recorded, which is all the write clock needs to be seeded with.
     *
     * The [FoldedState] deliberately stays unmaterialized: only writes and rescans need it, and
     * building it costs time in proportion to the library's whole edit history.
     */
    data class ProjectionCurrent(
        val maxHlc: Hlc,
    ) : OpenReconciliation

    /** The journal moved, or the stored projection is unusable. [state] must be projected. */
    data class Refolded(
        val state: FoldedState,
    ) : OpenReconciliation
}

/** Persisted source/event accumulator. Synced files remain authoritative; this cache is rebuildable. */
internal class MetadataAccumulator(
    private val fs: LibraryFileSystem,
    db: NpnDatabase,
) {
    private val dao = db.metadataProjectionDao()

    /** Judges whether the source set is safe to fold; see [SourceSetValidator]. */
    private val validator = SourceSetValidator(dao)

    /**
     * A journal source read and checked off disk, waiting for [ingestObject] (or a reconcile pass)
     * to record it. Reading is filesystem work, so the watch path does it before the transaction
     * that records it.
     */
    class StagedSource internal constructor(
        internal val relPath: String,
        internal val kind: SourceKind,
        internal val previouslyApplied: Boolean,
        internal val fingerprint: SourceFingerprint,
        internal val contentHash: String,
        internal val read: JournalSourceRead,
    )

    /**
     * Read the v2 object at [relPath] (watch path). The filename is the content hash, so reads are
     * hash-verified by [StorageV2Reader]. Null when the file is gone or already applied unchanged.
     */
    suspend fun readObject(relPath: String): StagedSource? {
        val path = JournalPaths.classify(relPath)
        require(path is JournalPath.ObjectV2) { "Not a storage-v2 object: $relPath" }
        val meta = fs.stat(relPath) ?: return null
        return stage(meta, path)
    }

    /**
     * What recording one source did: [events] are the ones no fold has seen yet (none on a no-op),
     * and [refold] says a stored event now holds a richer copy of itself, which a fold takes in only
     * by folding again in full ([dev.npn.core.db.IngestedEvents.enriched]).
     */
    class RecordedSource internal constructor(
        val events: List<JournalEvent>,
        val refold: Boolean,
    )

    /**
     * Read the source [meta] describes, classified as [path], unless it was applied before and is
     * unchanged since: null then. Unchanged means the same stat, an exact match on size and mtime
     * (mtime granularity means an approximate one could hide a mutation). Past that gate the bytes
     * are read, hashed and staged whatever their hash: an applied source whose stat moved is recorded
     * again with its new fingerprint, which leaves the next pass the new stat to match, and costs a
     * reconcile pass a full refold even when only the mtime changed ([IngestBatch.refold]). No DB
     * writes.
     */
    private suspend fun stage(
        meta: FileMeta,
        path: JournalPath,
    ): StagedSource? {
        val existing = dao.source(meta.relPath)
        val applied = existing?.status == SourceStatus.APPLIED
        if (applied && existing.fingerprint.sameStat(meta.size, meta.mtimeMillis)) return null
        val bytes = fs.readBytes(meta.relPath)
        val contentHash = sha256Bytes(bytes)
        val fingerprint = SourceFingerprint(meta.size, meta.mtimeMillis, contentHash)
        val (kind, read) = readSource(meta.relPath, path, bytes, contentHash)
        return StagedSource(
            relPath = meta.relPath,
            kind = kind,
            previouslyApplied = applied,
            fingerprint = fingerprint,
            contentHash = contentHash,
            read = read,
        )
    }

    /**
     * Record [staged]: its events when it read as valid, else why not, failing the pass. A source
     * applied before that no longer reads as valid fails without being recorded.
     */
    suspend fun record(staged: StagedSource): RecordedSource =
        when (val result = staged.read) {
            is JournalSourceRead.Accepted -> {
                val source =
                    MetadataSourceEntity(
                        path = staged.relPath,
                        kind = staged.kind,
                        fingerprint = staged.fingerprint,
                        contentHash = staged.contentHash,
                        status = SourceStatus.APPLIED,
                    )
                // A v2 object is one transaction, whose actor no other object may claim.
                val actor =
                    result.events
                        .firstOrNull()
                        ?.takeIf { staged.kind == SourceKind.OBJECT }
                        ?.let { MetadataTransactionActorEntity(it.event.dev, staged.contentHash) }
                val ingested = dao.ingest(source, result.events.map { it.toEventEntity() }, actor)
                RecordedSource(unseen(result.events, ingested.inserted), ingested.enriched)
            }

            is JournalSourceRead.Retryable -> {
                if (staged.previouslyApplied) {
                    fail(
                        WriteBlock.JOURNAL_DAMAGED,
                        "Previously applied metadata source is no longer valid: ${staged.relPath} (${result.message})",
                    )
                }
                dao.upsertSource(
                    MetadataSourceEntity(
                        path = staged.relPath,
                        kind = staged.kind,
                        fingerprint = staged.fingerprint,
                        contentHash = staged.contentHash,
                        status = SourceStatus.RETRYABLE,
                        diagnostic = result.message,
                    ),
                )
                fail(WriteBlock.JOURNAL_DAMAGED, result.message)
            }

            is JournalSourceRead.Unsupported -> {
                fail(WriteBlock.NEWER_FORMAT, result.message)
            }
        }

    /** What one [runReconcilePass] observed, before any decision about how to fold it. */
    private class ReconcilePass(
        val projectionCleared: Boolean,
        val ingest: IngestBatch,
    )

    /**
     * Forget every source and event recorded, so the next [reconcile] reads the folder's journal as
     * a fresh index's open does, as a [PROJECTION_VERSION] move makes it. The way out of a block on
     * an object nobody holds any more (`JournalCoordinator.rebuildIndex`): what only it held is
     * forgotten, as a device that never saw it never knew it.
     */
    suspend fun forgetSources() = dao.clearAccumulator()

    /**
     * Read the metadata directory and bring the persisted source/event tables up to date with it.
     * Shared by [reconcile] and [reconcileOnOpen]; decides nothing about the fold itself.
     */
    private suspend fun runReconcilePass(): ReconcilePass {
        val projectionCleared = dao.state()?.projectionVersion != PROJECTION_VERSION
        if (projectionCleared) {
            dao.clearAccumulator()
        }
        val entries = fs.listAllUnder(NpnPaths.JOURNAL_DIR)
        validator.newerStore(entries)?.let { fail(WriteBlock.NEWER_FORMAT, it) }
        validator.invalidObjectPath(entries)?.let { fail(WriteBlock.JOURNAL_DAMAGED, it) }
        val previous = dao.allSources()
        val files = entries.filter { !it.isDirectory }.associateBy { it.relPath }
        val ingest = ingestFiles(files.values)
        val gone = previous.filter { it.path !in files }
        if (gone.isNotEmpty()) {
            val listed =
                dao.allSources().filter { it.status == SourceStatus.APPLIED && it.path in files }.mapTo(HashSet()) {
                    it.path
                }
            validator.unheldEvents(gone.filter { it.status == SourceStatus.APPLIED }, listed)?.let {
                fail(WriteBlock.JOURNAL_DAMAGED, it)
            }
            // Every event of a gone source is held by a listed one now (the objects an earlier
            // build's compaction deleted, which its snapshot holds; a snapshot whose objects a
            // restore put back), and a gone file that never read as a source recorded none. The
            // index forgets each, as a rebuild would; the event table, and so the fold, stays as it was.
            gone.forEach { dao.forgetSource(it.path) }
        }
        return ReconcilePass(projectionCleared, ingest)
    }

    suspend fun reconcile(currentState: FoldedState? = null): MetadataReconciliation {
        val pass = runReconcilePass()
        val step =
            PassDecision.foldStep(
                hasFold = currentState != null,
                projectionCleared = pass.projectionCleared,
                sourcesRead = pass.ingest.read,
                refold = pass.ingest.refold,
            )
        val state =
            when (step) {
                // Nothing read: hand back whatever the caller already had, INCLUDING null. A caller
                // with no fold and nothing to fold in has nothing to project.
                FoldStep.KEEP -> {
                    currentState
                }

                FoldStep.EXTEND -> {
                    checkNotNull(
                        currentState,
                    ).also { current -> pass.ingest.newEvents.forEach(current::apply) }
                }

                FoldStep.REFOLD -> {
                    loadState()
                }
            }
        // Persist the projection version + observed maxHlc mid-pass so a crash before the
        // coordinator's markReady() still records progress, and the next open refolds rather than
        // reuse a projection this pass may have left half-written. Terminal states (ready/failed)
        // are written by markReady()/fail(). Skipped only when there is no fold to hand back (no
        // caller fold and nothing read), where the caller projects nothing and leaves the ready
        // marker, with the source-set fingerprint that lets the next open reuse the projection, as
        // it was. With a caller fold the marker goes to reconciling even when nothing was read, and
        // the coordinator's markReady() puts it back once the pass has projected.
        if (state != null) {
            dao.upsertState(projectionState(ProjectionStatus.RECONCILING, state.maxHlc))
        }
        val incremental = step == FoldStep.EXTEND
        return MetadataReconciliation(
            state = state,
            sourcesRead = pass.ingest.read,
            newEvents = if (incremental) pass.ingest.newEvents else emptyList(),
            incremental = incremental,
        )
    }

    /**
     * Reconcile at session open, reporting whether the stored projection can be reused as-is.
     *
     * Reuse needs three things to hold together: the projection was left in the ready terminal
     * state (so no earlier pass died half-applied), its version matches this build, and the
     * fingerprint it recorded for its source set still matches the set now on disk. A null stored
     * fingerprint (a projection that never reached ready) always refolds.
     */
    suspend fun reconcileOnOpen(): OpenReconciliation {
        val stored = dao.state()
        val pass = runReconcilePass()
        val reusable =
            !pass.projectionCleared &&
                stored != null &&
                stored.migrationState == ProjectionStatus.READY &&
                stored.sourceSetFingerprint == sourceSetFingerprint()
        if (reusable) {
            return OpenReconciliation.ProjectionCurrent(
                Hlc(stored.sourceMaxPhysical, stored.sourceMaxCounter, stored.sourceMaxDevice),
            )
        }
        val state = loadState()
        dao.upsertState(projectionState(ProjectionStatus.RECONCILING, state.maxHlc))
        return OpenReconciliation.Refolded(state)
    }

    suspend fun markReady(maxHlc: Hlc) {
        dao.upsertState(
            projectionState(ProjectionStatus.READY, maxHlc, sourceSetFingerprint = sourceSetFingerprint()),
        )
    }

    /**
     * Digest of the currently APPLIED source set: every path paired with its content fingerprint,
     * in path order. The folded state is a pure function of the events these sources carry, so two
     * passes that compute the same digest must fold to the same state.
     *
     * Restricting it to applied sources is what makes status changes visible: a source promoted
     * from retryable to applied (or demoted) enters or leaves the set and so moves the digest. A
     * source whose mtime changed but whose bytes did not also moves it, because the per-source
     * fingerprint embeds size and mtime — conservative in the safe direction, and self-correcting
     * on the next open once the new mtime is stored.
     */
    private suspend fun sourceSetFingerprint(): String {
        val applied =
            dao
                .allSources()
                .filter { it.status == SourceStatus.APPLIED }
                .sortedBy { it.path }
        val payload =
            buildString {
                for (source in applied) {
                    append(source.path)
                    append('\u0000')
                    append(source.fingerprint.value)
                    append('\n')
                }
            }
        return sha256Bytes(payload.encodeToByteArray())
    }

    /**
     * Fold ONLY the events of [operation]: a slice of the state that is needed without the whole
     * journal. The library settings are one, read at open time (the review mode and standard-field
     * choices drive the UI): a handful of events against a table holding every event ever recorded,
     * so the reuse path need not materialize the whole state for them.
     */
    suspend fun loadStateOf(operation: String): FoldedState = fold(dao.eventsForOperation(operation))

    suspend fun persistAllocator(
        physicalMillis: Long,
        counter: Int,
    ) {
        val current = dao.state() ?: projectionState(ProjectionStatus.RECONCILING, Hlc.ZERO)
        dao.upsertState(current.copy(allocatorPhysical = physicalMillis, allocatorCounter = counter))
    }

    /** Running result of an [ingestFiles] pass. */
    private class IngestBatch {
        var read = 0
        val newEvents = ArrayList<JournalEvent>()

        // True if any source that was already APPLIED got re-ingested (mutated in place), which can
        // drop events (MetadataProjectionDao.replaceSourceEvents), or a source replaced a stored
        // event with a richer copy of it, which the in-memory state folded as it was. Either way
        // that state plus this pass's new events is not the store -> the caller must fully refold
        // rather than apply new events.
        var refold = false
    }

    private suspend fun ingestFiles(files: Collection<FileMeta>): IngestBatch {
        val batch = IngestBatch()
        val sources =
            files.sortedBy { it.relPath }.filter { meta ->
                val path = JournalPaths.classify(meta.relPath)
                path !is JournalPath.Staging && path !is JournalPath.SyncTemp && path !is JournalPath.Unknown
            }
        for (meta in sources) ingestFile(meta, batch)
        return batch
    }

    private suspend fun ingestFile(
        meta: FileMeta,
        batch: IngestBatch,
    ) {
        val path = JournalPaths.classify(meta.relPath)
        if (path is JournalPath.UnsupportedObjectStore) {
            fail(WriteBlock.NEWER_FORMAT, "Unsupported metadata object store version ${path.version}")
        }
        val staged = stage(meta, path) ?: return
        val recorded = record(staged)
        batch.read++
        batch.newEvents.addAll(recorded.events)
        if (staged.previouslyApplied || recorded.refold) batch.refold = true
    }

    /**
     * A transaction this device just wrote, staged for [recordLocalSource]: its source row, canonical
     * events and actor, and the [written] events themselves. Staging stats the written file, so it
     * happens before the transaction that records it.
     */
    class LocalSource internal constructor(
        internal val source: MetadataSourceEntity,
        internal val events: List<MetadataEventEntity>,
        internal val actor: MetadataTransactionActorEntity?,
        internal val written: List<JournalEvent>,
    )

    suspend fun localSource(published: PublishedTransaction): LocalSource {
        val events = published.events
        val canonical = events.map(EventDecoderRegistry::canonical)
        // The written bytes are needed only when the stat failed.
        val bytes by lazy { JournalCodec.encodeLines(events).encodeToByteArray() }
        val meta = fs.stat(published.relPath)
        val source =
            MetadataSourceEntity(
                path = published.relPath,
                kind = SourceKind.OBJECT,
                fingerprint =
                    SourceFingerprint(
                        meta?.size ?: bytes.size.toLong(),
                        meta?.mtimeMillis ?: 0L,
                        published.contentHash,
                    ),
                contentHash = published.contentHash,
                status = SourceStatus.APPLIED,
            )
        val actor = MetadataTransactionActorEntity(events.first().dev, published.contentHash)
        return LocalSource(source, canonical.map { it.toEventEntity() }, actor, events)
    }

    suspend fun recordLocalSource(local: LocalSource) {
        dao.ingest(local.source, local.events, local.actor)
    }

    suspend fun loadState(): FoldedState = fold(dao.allEvents())

    private fun fold(events: List<MetadataEventEntity>): FoldedState =
        FoldedState().also { state ->
            events.forEach { stored -> state.apply(EventDecoderRegistry.decodeStored(stored.canonicalJson)) }
        }

    private suspend fun fail(
        block: WriteBlock,
        message: String,
    ): Nothing {
        dao.upsertState(projectionState(ProjectionStatus.FAILED, Hlc.ZERO, message))
        throw MetadataWriteBlocked(block, message)
    }

    private suspend fun projectionState(
        status: ProjectionStatus,
        maxHlc: Hlc,
        diagnostic: String? = null,
        sourceSetFingerprint: String? = null,
    ): MetadataProjectionStateEntity {
        val previous = dao.state()
        return MetadataProjectionStateEntity(
            projectionVersion = PROJECTION_VERSION,
            migrationState = status,
            sourceMaxPhysical = maxHlc.physicalMillis,
            sourceMaxCounter = maxHlc.counter,
            sourceMaxDevice = maxHlc.deviceId,
            allocatorPhysical = previous?.allocatorPhysical ?: maxHlc.physicalMillis,
            allocatorCounter = previous?.allocatorCounter ?: maxHlc.counter,
            diagnostic = diagnostic,
            // Only a ready projection may advertise a reusable source set; every transient or
            // failed state clears it so the next open refolds rather than trusting a half-pass.
            sourceSetFingerprint = sourceSetFingerprint,
        )
    }

    companion object {
        /**
         * Version of the accumulator + projection contract. A stored projection whose version does
         * not match this build is wiped and rebuilt from the journal.
         *
         * BUMP THIS whenever a change alters what a fold produces from the same events — a new or
         * differently-derived column in `FoldProjection.apply`, a changed OR-set/LWW rule, a fix to
         * how an op projects. [reconcileOnOpen] reuses a stored projection whenever the source set
         * is unchanged, so without a bump an existing install keeps serving rows built by the OLD
         * fold and never notices.
         *
         * Not needed for changes that only affect ingestion/validation, or for Room schema changes
         * that already drop the affected tables. Bump it too when a [SourceKind]
         * goes, so the source rows of that kind are cleared before any is read.
         */
        const val PROJECTION_VERSION = 14
    }
}

/** The events of [read] that its ingest [inserted]: the ones no fold has seen yet. */
private fun unseen(
    read: List<CanonicalJournalEvent>,
    inserted: List<MetadataEventEntity>,
): List<JournalEvent> {
    if (inserted.isEmpty()) return emptyList()
    val keys = inserted.mapTo(HashSet()) { it.deviceId to it.sequence }
    return read.filter { (it.event.dev to it.event.seq) in keys }.map { it.event }
}

/**
 * Read a source whose bytes the accumulator loaded and hashed, with the kind of source a journal
 * file classified as [path] is; the v2 readers take that hash.
 */
private fun readSource(
    relPath: String,
    path: JournalPath,
    bytes: ByteArray,
    contentHash: String,
): Pair<SourceKind, JournalSourceRead> =
    when (path) {
        is JournalPath.SnapshotV2 -> SourceKind.SNAPSHOT to SnapshotV2Reader.read(relPath, bytes, contentHash)

        is JournalPath.ObjectV2 -> SourceKind.OBJECT to StorageV2Reader.read(relPath, bytes, contentHash)

        // The caller stages only the two above.
        is JournalPath.UnsupportedObjectStore, JournalPath.Staging, JournalPath.SyncTemp, JournalPath.Unknown,
        -> error("Not a journal source: $relPath")
    }

/** The row an event is stored as in `metadata_events`. */
internal fun CanonicalJournalEvent.toEventEntity(): MetadataEventEntity =
    MetadataEventEntity(
        deviceId = event.dev,
        sequence = event.seq,
        canonicalJson = canonicalJson,
        hlcPhysical = event.hlc.physicalMillis,
        hlcCounter = event.hlc.counter,
        hlcDevice = event.hlc.deviceId,
        documentId = event.doc,
        operation = event.op.wire,
    )
