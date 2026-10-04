package dev.npn.core.index

import androidx.room3.withWriteTransaction
import dev.npn.core.db.MetadataEventEntity
import dev.npn.core.db.MetadataProjectionDao
import dev.npn.core.db.MetadataProjectionStateEntity
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.MetadataTransactionActorEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.journal.CanonicalJournalEvent
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.JournalSourceRead
import dev.npn.core.journal.SnapshotV2Reader
import dev.npn.core.journal.StorageV1Reader
import dev.npn.core.journal.StorageV2Reader
import dev.npn.core.journal.systemNowMillis

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
    val newEvents: List<dev.npn.core.journal.JournalEvent> = emptyList(),
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
     * paying for it here is what made opening a library scale with a library's entire edit
     * history rather than with its current contents.
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
    private val db: NpnDatabase,
    private val installationId: String? = null,
) {
    private val dao = db.metadataProjectionDao()
    private var stagingCleaned = false

    /** Judges whether the source set is safe to fold; see [SourceSetValidator]. */
    private val validator = SourceSetValidator(dao)

    suspend fun <T> withProjectionTransaction(block: suspend () -> T): T = db.withWriteTransaction { block() }

    /**
     * Shared stat/read/hash/fingerprint core for the two ingest paths ([ingestObject] and
     * [ingestFile]). Applies both cheap gates — an already-applied source whose size+mtime match
     * skips the read entirely, and one whose full fingerprint matches skips re-ingest — then reads
     * the bytes and computes `"size,mtime:hash"`. Returns [Unchanged] when a gate fires, else a
     * [Loaded] carrying the bytes/hash/fingerprint the caller ingests. Pure of any DB writes.
     */
    private sealed interface PreparedSource {
        data object Unchanged : PreparedSource

        data class Loaded(
            val bytes: ByteArray,
            val contentHash: String,
            val fingerprint: String,
        ) : PreparedSource
    }

    private suspend fun prepareSource(
        relPath: String,
        meta: FileMeta,
        existingSource: MetadataSourceEntity?,
    ): PreparedSource {
        val alreadyApplied =
            existingSource != null &&
                existingSource.status == MetadataProjectionDao.STATUS_APPLIED
        // Cheap gate: an already-applied source whose size+mtime are byte-for-byte unchanged is
        // treated as unchanged without reading/hashing. Only an exact match on BOTH fields
        // short-circuits (mtime granularity means an approximate match could hide a mutation), so
        // any difference falls through to a full read + hash.
        val statPrefix = existingSource?.fingerprint?.substringBefore(':', missingDelimiterValue = "")
        if (alreadyApplied && statPrefix == "${meta.size},${meta.mtimeMillis}") return PreparedSource.Unchanged
        val bytes = fs.readBytes(relPath)
        val contentHash = sha256Bytes(bytes)
        val fingerprint = "${meta.size},${meta.mtimeMillis}:$contentHash"
        if (alreadyApplied && existingSource.fingerprint == fingerprint) return PreparedSource.Unchanged
        return PreparedSource.Loaded(bytes, contentHash, fingerprint)
    }

    /**
     * v2-only single-object ingest (watch path). The filename is the content hash, so reads are
     * hash-verified by [StorageV2Reader]. Returns the newly-unseen events (empty on a no-op).
     */
    suspend fun ingestObject(relPath: String): List<dev.npn.core.journal.JournalEvent> {
        val path = NpnPaths.journalPath(relPath)
        if (path is NpnPaths.JournalPath.UnsupportedObjectStore) {
            fail("Unsupported metadata object store version ${path.version}")
        }
        require(path is NpnPaths.JournalPath.ObjectV2) { "Not a storage-v2 object: $relPath" }
        val meta = fs.stat(relPath) ?: return emptyList()
        val existingSource = dao.source(relPath)
        val loaded =
            when (val prepared = prepareSource(relPath, meta, existingSource)) {
                PreparedSource.Unchanged -> return emptyList()
                is PreparedSource.Loaded -> prepared
            }
        return when (val result = StorageV2Reader.read(relPath, loaded.bytes)) {
            is JournalSourceRead.Accepted -> {
                val unseen = result.events.filter { dao.event(it.event.dev, it.event.seq) == null }
                dao.ingestSource(
                    MetadataSourceEntity(
                        relPath,
                        NpnPaths.STORAGE_V2,
                        loaded.fingerprint,
                        loaded.contentHash,
                        MetadataProjectionDao.STATUS_APPLIED,
                    ),
                    result.events.map(::eventEntity),
                    MetadataTransactionActorEntity(
                        result.events
                            .first()
                            .event.dev,
                        loaded.contentHash,
                    ),
                )
                unseen.map { it.event }
            }

            is JournalSourceRead.Retryable -> {
                if (existingSource?.status == MetadataProjectionDao.STATUS_APPLIED) {
                    fail("Previously applied object is no longer valid: $relPath (${result.message})")
                }
                dao.upsertSource(
                    MetadataSourceEntity(
                        relPath,
                        NpnPaths.STORAGE_V2,
                        loaded.fingerprint,
                        loaded.contentHash,
                        MetadataProjectionDao.STATUS_RETRYABLE,
                        result.message,
                    ),
                )
                fail(result.message)
            }

            is JournalSourceRead.Unsupported -> {
                fail(result.message)
            }
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
        cleanOwnedStaging(entries)
        validator.unsupportedStorage(entries)?.let { fail(it) }
        val files = entries.filter { !it.isDirectory }.associateBy { it.relPath }
        val previous = dao.allSources()

        val ingest = ingestFiles(files.values)
        validator.unexplainedSourceLoss(previous, files)?.let { fail(it) }

        // A source present before but gone now (even with a validated replacement above) means the
        // event graph may have shrunk relative to a caller's in-memory state.
        return ReconcilePass(projectionCleared, ingest, previous.any { it.path !in files })
    }

    suspend fun reconcile(currentState: FoldedState? = null): MetadataReconciliation {
        val pass = runReconcilePass()

        // A shrunken event graph, a wiped projection, a re-ingested (mutated) existing source, or
        // a first load with no prior state all force a full refold. Only when every read this pass
        // purely ADDED events onto an intact prior state is incremental projection sound.
        val incremental =
            currentState != null && !pass.projectionCleared &&
                !pass.ingest.reingestedExisting && !pass.sourceRemoved

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
            dao.upsertState(projectionState(MIGRATION_RECONCILING, state.maxHlc))
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
     * fingerprint — a row written before this optimization existed, or one that never reached
     * ready — always refolds, so the first open after an upgrade pays once and later opens do not.
     */
    suspend fun reconcileOnOpen(): OpenReconciliation {
        val stored = dao.state()
        val pass = runReconcilePass()
        val reusable =
            !pass.projectionCleared &&
                stored != null &&
                stored.migrationState == MIGRATION_READY &&
                stored.sourceSetFingerprint != null &&
                stored.sourceSetFingerprint == sourceSetFingerprint()
        if (reusable) {
            return OpenReconciliation.ProjectionCurrent(
                Hlc(stored.sourceMaxPhysical, stored.sourceMaxCounter, stored.sourceMaxDevice),
            )
        }
        val state = loadState()
        dao.upsertState(projectionState(MIGRATION_RECONCILING, state.maxHlc))
        return OpenReconciliation.Refolded(state)
    }

    suspend fun markReady(maxHlc: Hlc) {
        dao.upsertState(
            projectionState(MIGRATION_READY, maxHlc, sourceSetFingerprint = sourceSetFingerprint()),
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
                .filter { it.status == MetadataProjectionDao.STATUS_APPLIED }
                .sortedBy { it.path }
        val payload =
            buildString {
                for (source in applied) {
                    append(source.path)
                    append('\u0000')
                    append(source.fingerprint)
                    append('\n')
                }
            }
        return sha256Bytes(payload.encodeToByteArray())
    }

    /**
     * Fold ONLY the events of [operations]: a slice of the state that is needed without the
     * whole journal. The library settings are one, read at open time (the review mode and
     * standard-field choices drive the UI); the trash lifecycle is another, read by the trash
     * auto-purge for documents with no row. Either is a handful of events against a table holding
     * every event ever recorded, so the reuse path need not materialize the whole state for them.
     */
    suspend fun loadStateOf(vararg operations: String): FoldedState =
        FoldedState().also { state ->
            for (operation in operations) {
                dao.eventsForOperation(operation).forEach { stored ->
                    state.apply(EventDecoderRegistry.decode(stored.canonicalJson, allowMissingV1 = false).event)
                }
            }
        }

    suspend fun persistAllocator(
        physicalMillis: Long,
        counter: Int,
    ) {
        val current = dao.state() ?: projectionState(MIGRATION_RECONCILING, Hlc.ZERO)
        dao.upsertState(current.copy(allocatorPhysical = physicalMillis, allocatorCounter = counter))
    }

    private suspend fun cleanOwnedStaging(entries: List<FileMeta>) {
        if (stagingCleaned) return
        stagingCleaned = true
        val marker = installationId?.let { ".$it." } ?: return
        val cutoff = systemNowMillis() - STAGING_MAX_AGE_MS
        entries
            .asSequence()
            .filter { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.Staging }
            .filter { it.name.contains(marker) }
            .filter { it.mtimeMillis in 1 until cutoff }
            .forEach { runCatching { fs.delete(it.relPath) } }
    }

    /** Running result of an [ingestFiles] pass. */
    private class IngestBatch {
        var read = 0
        val newEvents = ArrayList<dev.npn.core.journal.JournalEvent>()

        // True if any source that was already APPLIED got re-ingested (mutated in place). Such a
        // rewrite can drop events (deleteUnreferencedEvents), so the in-memory state may no longer
        // be a strict superset -> the caller must fully refold rather than apply new events.
        var reingestedExisting = false
    }

    private suspend fun ingestFiles(files: Collection<FileMeta>): IngestBatch {
        val batch = IngestBatch()
        val sources =
            files.sortedBy { it.relPath }.filter { meta ->
                val path = NpnPaths.journalPath(meta.relPath)
                path !is NpnPaths.JournalPath.Staging && path !is NpnPaths.JournalPath.Unknown
            }
        for (meta in sources) ingestFile(meta, batch)
        return batch
    }

    private suspend fun ingestFile(
        meta: FileMeta,
        batch: IngestBatch,
    ) {
        val path = NpnPaths.journalPath(meta.relPath)
        if (path is NpnPaths.JournalPath.UnsupportedObjectStore) {
            fail("Unsupported metadata object store version ${path.version}")
        }
        val existing = dao.source(meta.relPath)
        val alreadyApplied = existing != null && existing.status == MetadataProjectionDao.STATUS_APPLIED
        // v1-tolerant shrink guard: a legacy (mutable) source is allowed to grow but never shrink.
        // Runs before the read; when the stat gate below short-circuits the size is unchanged, so
        // this is a no-op there and only fires on a genuine shrink.
        if (alreadyApplied && existing.storageVersion == NpnPaths.STORAGE_V1) {
            val previousSize = existing.fingerprint.substringBefore(',').toLongOrNull()
            if (previousSize != null && meta.size < previousSize) {
                fail("Legacy metadata source shrank: ${meta.relPath}")
            }
        }
        // Shared stat/read/hash/fingerprint core + cheap gates. v1 mutation semantics are
        // preserved: a changed size/mtime still falls through to the read + rewrite logic below.
        val loaded =
            when (val prepared = prepareSource(meta.relPath, meta, existing)) {
                PreparedSource.Unchanged -> return
                is PreparedSource.Loaded -> prepared
            }
        val storageVersion =
            when (path) {
                is NpnPaths.JournalPath.SnapshotV2 -> NpnPaths.STORAGE_SNAPSHOT
                is NpnPaths.JournalPath.ObjectV2 -> NpnPaths.STORAGE_V2
                else -> NpnPaths.STORAGE_V1
            }
        val result = readSource(meta.relPath, path, loaded.bytes)
        if (result is JournalSourceRead.Retryable && alreadyApplied) {
            fail("Previously applied metadata source is no longer valid: ${meta.relPath} (${result.message})")
        }
        if (alreadyApplied) batch.reingestedExisting = true
        val supersedes =
            if (path is NpnPaths.JournalPath.SnapshotV2 && result is JournalSourceRead.Accepted) {
                SnapshotV2Reader
                    .readSnapshot(meta.relPath, loaded.bytes)
                    ?.manifest
                    ?.supersedes
                    .orEmpty()
            } else {
                null
            }
        applyReadResult(meta.relPath, storageVersion, loaded.fingerprint, loaded.contentHash, result, batch, supersedes)
    }

    private fun readSource(
        relPath: String,
        path: NpnPaths.JournalPath,
        bytes: ByteArray,
    ): JournalSourceRead =
        when (path) {
            is NpnPaths.JournalPath.SnapshotV2 -> SnapshotV2Reader.read(relPath, bytes)
            is NpnPaths.JournalPath.ObjectV2 -> StorageV2Reader.read(relPath, bytes)
            else -> StorageV1Reader.read(bytes)
        }

    private suspend fun applyReadResult(
        path: String,
        storageVersion: Int,
        fingerprint: String,
        contentHash: String,
        result: JournalSourceRead,
        batch: IngestBatch,
        snapshotSupersedes: List<String>? = null,
    ) {
        when (result) {
            is JournalSourceRead.Accepted -> {
                val diagnostic =
                    result.diagnostics
                        .joinToString("\n") { item ->
                            item.line?.let { "line $it: ${item.message}" } ?: item.message
                        }.ifEmpty { null }
                val hashCol = contentHash.takeIf { storageVersion != NpnPaths.STORAGE_V1 }
                val source =
                    MetadataSourceEntity(
                        path,
                        storageVersion,
                        fingerprint,
                        hashCol,
                        MetadataProjectionDao.STATUS_APPLIED,
                        diagnostic,
                    )
                val unseen = result.events.filter { dao.event(it.event.dev, it.event.seq) == null }
                if (snapshotSupersedes != null) {
                    dao.ingestSnapshot(source, result.events.map(::eventEntity), snapshotSupersedes)
                } else {
                    val actor =
                        result.events
                            .firstOrNull()
                            ?.event
                            ?.dev
                            ?.takeIf { storageVersion == NpnPaths.STORAGE_V2 }
                            ?.let { MetadataTransactionActorEntity(it, contentHash) }
                    dao.ingestSource(source, result.events.map(::eventEntity), actor)
                }
                batch.read++
                batch.newEvents.addAll(unseen.map { it.event })
            }

            is JournalSourceRead.Retryable -> {
                dao.upsertSource(
                    MetadataSourceEntity(
                        path,
                        storageVersion,
                        fingerprint,
                        contentHash,
                        MetadataProjectionDao.STATUS_RETRYABLE,
                        result.message,
                    ),
                )
                fail(result.message)
            }

            is JournalSourceRead.Unsupported -> {
                fail(result.message)
            }
        }
    }

    suspend fun recordLocalSource(
        path: String,
        storageVersion: Int,
        contentHash: String?,
        events: List<dev.npn.core.journal.JournalEvent>,
    ) {
        val canonical =
            events.map { event ->
                EventDecoderRegistry.decode(JournalCodec.encode(event), allowMissingV1 = false)
            }
        val bytes = JournalCodec.encodeLines(events).encodeToByteArray()
        val hash = contentHash ?: sha256Bytes(bytes)
        val meta = fs.stat(path)
        val source =
            MetadataSourceEntity(
                path = path,
                storageVersion = storageVersion,
                fingerprint = "${meta?.size ?: bytes.size.toLong()},${meta?.mtimeMillis ?: 0L}:$hash",
                contentHash = contentHash,
                status = MetadataProjectionDao.STATUS_APPLIED,
            )
        val actor =
            if (storageVersion == NpnPaths.STORAGE_V2) {
                MetadataTransactionActorEntity(events.first().dev, checkNotNull(contentHash))
            } else {
                null
            }
        dao.ingestSource(source, canonical.map(::eventEntity), actor)
    }

    suspend fun loadState(): FoldedState =
        FoldedState().also { state ->
            dao.allEvents().forEach { stored ->
                state.apply(EventDecoderRegistry.decode(stored.canonicalJson, allowMissingV1 = false).event)
            }
        }

    /** Compaction bookkeeping (snapshot record/plan/delete) lives in [SnapshotSupport]. */
    val snapshotSupport: SnapshotSupport by lazy { SnapshotSupport(fs, dao, ::eventEntity) }

    private suspend fun fail(message: String): Nothing {
        dao.upsertState(projectionState(MIGRATION_FAILED, Hlc.ZERO, message))
        throw MetadataWriteBlocked(message)
    }

    private suspend fun projectionState(
        status: String,
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

    private fun eventEntity(canonical: CanonicalJournalEvent): MetadataEventEntity {
        val event = canonical.event
        return MetadataEventEntity(
            deviceId = event.dev,
            sequence = event.seq,
            canonicalJson = canonical.canonicalJson,
            hlcPhysical = event.hlc.physicalMillis,
            hlcCounter = event.hlc.counter,
            hlcDevice = event.hlc.deviceId,
            documentId = event.doc,
            operation = event.op.wire,
        )
    }

    companion object {
        /**
         * Version of the accumulator + projection contract. A stored projection whose version does
         * not match this build is wiped and rebuilt from the journal.
         *
         * BUMP THIS whenever a change alters what a fold produces from the same events — a new or
         * differently-derived column in `Indexer.applyFold`, a changed OR-set/LWW rule, a fix to
         * how an op projects. It is no longer optional: [reconcileOnOpen] reuses a stored
         * projection whenever the source set is unchanged, so without a bump an existing install
         * keeps serving rows built by the OLD fold and never notices. (Before that reuse existed
         * every launch reprojected, and such changes healed themselves on the next open.)
         *
         * Not needed for changes that only affect ingestion/validation, or for Room schema changes
         * that already drop the affected tables.
         */
        const val PROJECTION_VERSION = 4
        private const val STAGING_MAX_AGE_MS = 60L * 60L * 1000L

        /** Transient marker while a reconcile is in flight; not observed by any caller. */
        const val MIGRATION_RECONCILING = "reconciling"

        /** Terminal: reconcile completed and the coordinator marked the projection ready. */
        const val MIGRATION_READY = "ready-v2"

        /** Terminal: reconcile failed; writes are latched off by the coordinator. */
        const val MIGRATION_FAILED = "failed"
    }
}
