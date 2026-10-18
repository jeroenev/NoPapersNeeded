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
import dev.npn.core.journal.StorageV1Reader
import dev.npn.core.journal.StorageV2Reader

class MetadataWriteBlocked(
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
data class MetadataReconciliation(
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
sealed interface OpenReconciliation {
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
class MetadataAccumulator(
    private val fs: LibraryFileSystem,
    db: NpnDatabase,
    /** Deletes what an applied snapshot on disk superseded; the coordinator's compaction shares it. */
    private val snapshots: SnapshotSupport = SnapshotSupport(fs, db.metadataProjectionDao()),
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
     * unchanged since: null then. The cheap gate is the stat, an exact match on size and mtime
     * (mtime granularity means an approximate one could hide a mutation); the bytes are read and
     * hashed only past it, and a matching hash skips the source too. No DB writes.
     */
    private suspend fun stage(
        meta: FileMeta,
        path: JournalPath,
    ): StagedSource? {
        val existing = dao.source(meta.relPath)
        val applied = existing?.status == SourceStatus.APPLIED
        // v1-tolerant shrink guard: a legacy (mutable) source is allowed to grow but never shrink.
        // When the stat gate below fires the size is unchanged, so this only fires on a real shrink.
        if (applied && existing.kind == SourceKind.LEGACY_SEGMENT) {
            val previousSize = existing.fingerprint.size
            if (previousSize != null && meta.size < previousSize) {
                fail("Legacy metadata source shrank: ${meta.relPath}")
            }
        }
        if (applied && existing.fingerprint.sameStat(meta.size, meta.mtimeMillis)) return null
        val bytes = fs.readBytes(meta.relPath)
        val contentHash = sha256Bytes(bytes)
        val fingerprint = SourceFingerprint(meta.size, meta.mtimeMillis, contentHash)
        if (applied && existing.fingerprint == fingerprint) return null
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
                        contentHash = staged.contentHash.takeIf { staged.kind != SourceKind.LEGACY_SEGMENT },
                        status = SourceStatus.APPLIED,
                        diagnostic = diagnosticOf(result),
                    )
                // A v2 object is one transaction, whose actor no other object may claim.
                val actor =
                    result.events
                        .firstOrNull()
                        ?.takeIf { staged.kind == SourceKind.OBJECT }
                        ?.let { MetadataTransactionActorEntity(it.event.dev, staged.contentHash) }
                val supersedes = result.manifest?.supersedes.orEmpty()
                val ingested = dao.ingest(source, result.events.map { it.toEventEntity() }, actor, supersedes)
                RecordedSource(unseen(result.events, ingested.inserted), ingested.enriched)
            }

            is JournalSourceRead.Retryable -> {
                if (staged.previouslyApplied) {
                    fail("Previously applied metadata source is no longer valid: ${staged.relPath} (${result.message})")
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
                fail(result.message)
            }

            is JournalSourceRead.Unsupported -> {
                fail(result.message)
            }
        }

    /** What one [runReconcilePass] observed, before any decision about how to fold it. */
    private class ReconcilePass(
        val projectionCleared: Boolean,
        val ingest: IngestBatch,
        val sourceRemoved: Boolean,
    )

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
        validator.unsupportedStorage(entries)?.let { fail(it) }
        val previous = dao.allSources()
        val listed = entries.filter { !it.isDirectory }.associateBy { it.relPath }

        // A leftover whose events a snapshot on disk carries is not read, whatever its own bytes are
        // now (a sync conflict may have written over it). The snapshot is read first, so one that
        // changed or no longer reads fails the pass before any file it supersedes is deleted, and
        // only a snapshot on disk now supersedes anything, and only what it holds the events of.
        val ingest = ingestFiles((listed - snapshots.supersededLeftovers(listed.keys, previous)).values)
        val swept = snapshots.deleteSupersededLeftovers(listed.keys, previous)
        val files = listed - swept
        val current = dao.allSources()
        val carried = snapshots.carriedByListed(files.keys, current)
        validator.unexplainedSourceLoss(previous, current, files, carried.hashes)?.let { fail(it) }
        // A compaction a restore undid: its objects are back and hold its events, so the index
        // forgets the snapshot as a rebuild would, and a later compaction replaces them again.
        carried.undone.forEach { dao.forgetSource(it) }

        // A source gone with its events still on disk elsewhere (the objects a compaction deleted,
        // which its snapshot carries) shrinks nothing: its rows and events stay, and a caller's
        // in-memory state still is their fold. Any other loss, or a forgotten snapshot, may have.
        val sourceRemoved =
            carried.undone.isNotEmpty() ||
                previous.any { it.path !in files && it.contentHash !in carried.hashes }
        return ReconcilePass(projectionCleared, ingest, sourceRemoved)
    }

    suspend fun reconcile(currentState: FoldedState? = null): MetadataReconciliation {
        val pass = runReconcilePass()

        // A shrunken event graph, a wiped projection, a re-ingested (mutated) existing source, a
        // richer copy of a stored event, or a first load with no prior state all force a full
        // refold. Only when every read this pass purely ADDED events onto an intact prior state is
        // incremental projection sound.
        val incremental =
            currentState != null && !pass.projectionCleared &&
                !pass.ingest.refold && !pass.sourceRemoved

        val state =
            when {
                // Nothing read: hand back whatever the caller already had, INCLUDING null. A caller
                // with no fold and nothing to fold in has nothing to project.
                pass.ingest.read == 0 -> {
                    currentState
                }

                currentState != null && incremental -> {
                    currentState.also { current -> pass.ingest.newEvents.forEach(current::apply) }
                }

                else -> {
                    loadState()
                }
            }
        // Persist the projection version + observed maxHlc mid-pass so a crash before the
        // coordinator's markReady() still records progress. Terminal states (ready/failed) are
        // written by markReady()/fail(); this transient marker is not observed by any caller.
        // Skipped entirely on a no-op pass: it would otherwise overwrite the ready marker (and the
        // source-set fingerprint that lets the next open reuse the projection) for no change.
        if (state != null) {
            dao.upsertState(projectionState(ProjectionStatus.RECONCILING, state.maxHlc))
        }
        return MetadataReconciliation(
            state = state,
            sourcesRead = pass.ingest.read,
            newEvents = if (incremental) pass.ingest.newEvents else emptyList(),
            incremental = incremental && pass.ingest.read > 0,
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
            fail("Unsupported metadata object store version ${path.version}")
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

    private suspend fun fail(message: String): Nothing {
        dao.upsertState(projectionState(ProjectionStatus.FAILED, Hlc.ZERO, message))
        throw MetadataWriteBlocked(message)
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
         * that already drop the affected tables.
         */
        const val PROJECTION_VERSION = 10
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

/** The lines a tolerant read skipped, one per line, or null when it skipped none. */
private fun diagnosticOf(read: JournalSourceRead.Accepted): String? =
    read.diagnostics
        .joinToString("\n") { item -> item.line?.let { "line $it: ${item.message}" } ?: item.message }
        .ifEmpty { null }

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

        JournalPath.Legacy -> SourceKind.LEGACY_SEGMENT to StorageV1Reader.read(bytes)

        // The caller stages only the three above.
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
