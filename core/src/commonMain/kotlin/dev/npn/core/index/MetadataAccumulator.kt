package dev.npn.core.index

import androidx.room3.withWriteTransaction
import dev.npn.core.db.MetadataEventEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.MetadataProjectionDao
import dev.npn.core.db.MetadataProjectionStateEntity
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.MetadataTransactionActorEntity
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.FileMeta
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

class MetadataWriteBlocked(message: String) : IllegalStateException(message)

/**
 * Outcome of a [MetadataAccumulator.reconcile] pass.
 *
 * @property state the folded state to project (either the caller's in-memory state with
 *   [newEvents] already applied, or a full refold from the DB).
 * @property sourcesRead number of source files that were (re)ingested this pass.
 * @property newEvents the journal events newly applied to [state] this pass, in the order they
 *   were ingested. Only meaningful when [incremental] is true.
 * @property incremental true when [state] is the caller's prior state with only [newEvents]
 *   added (no source removed/invalidated, no event dropped, no full refold), so callers may
 *   project just [newEvents] via a bounded reprojection. When false the caller must fully
 *   reproject: [state] was rebuilt from the DB and may differ arbitrarily from its prior value.
 */
data class MetadataReconciliation(
    val state: FoldedState,
    val sourcesRead: Int,
    val newEvents: List<dev.npn.core.journal.JournalEvent> = emptyList(),
    val incremental: Boolean = false,
)

/** Persisted source/event accumulator. Synced files remain authoritative; this cache is rebuildable. */
class MetadataAccumulator(
    private val fs: LibraryFileSystem,
    private val db: NpnDatabase,
    private val installationId: String? = null,
) {
    private val dao = db.metadataProjectionDao()
    private var stagingCleaned = false

    /**
     * Pending-supersession bookkeeping: a previously-applied source whose file disappeared and
     * whose content hash is NOT yet covered by an applied snapshot. Its events are still in the DB
     * (they were applied), so a deletion arriving before its justifying snapshot must NOT hard-block
     * writes. We count how many consecutive reconciles each such path stays unexplained and only
     * escalate to the hard block past [PENDING_SUPERSESSION_LIMIT] (a deletion that never gets a
     * snapshot is real damage). The counter is device-local and rebuildable; a fresh session
     * legitimately restarts it.
     */
    private val pendingSupersession = HashMap<String, Int>()

    suspend fun <T> withProjectionTransaction(block: suspend () -> T): T =
        db.withWriteTransaction { block() }

    /**
     * Shared stat/read/hash/fingerprint core for the two ingest paths ([ingestObject] and
     * [ingestFile]). Applies both cheap gates — an already-applied source whose size+mtime match
     * skips the read entirely, and one whose full fingerprint matches skips re-ingest — then reads
     * the bytes and computes `"size,mtime:hash"`. Returns [Unchanged] when a gate fires, else a
     * [Loaded] carrying the bytes/hash/fingerprint the caller ingests. Pure of any DB writes.
     */
    private sealed interface PreparedSource {
        data object Unchanged : PreparedSource
        data class Loaded(val bytes: ByteArray, val contentHash: String, val fingerprint: String) : PreparedSource
    }

    private suspend fun prepareSource(
        relPath: String,
        meta: FileMeta,
        existingSource: MetadataSourceEntity?,
    ): PreparedSource {
        val alreadyApplied = existingSource != null &&
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
        val loaded = when (val prepared = prepareSource(relPath, meta, existingSource)) {
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
                    MetadataTransactionActorEntity(result.events.first().event.dev, loaded.contentHash),
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
            is JournalSourceRead.Unsupported -> fail(result.message)
        }
    }

    suspend fun reconcile(currentState: FoldedState? = null): MetadataReconciliation {
        val projectionCleared = dao.state()?.projectionVersion != PROJECTION_VERSION
        if (projectionCleared) {
            dao.clearAccumulator()
        }
        val entries = fs.listAllUnder(NpnPaths.JOURNAL_DIR)
        cleanOwnedStaging(entries)
        validateStorageVersions(entries)
        val files = entries.filter { !it.isDirectory }.associateBy { it.relPath }
        val previous = dao.allSources()

        val ingest = ingestFiles(files.values)
        validatePublishedObjects(previous, files)

        // A source present before but gone now (even with a validated replacement above) means the
        // event graph may have shrunk relative to the caller's in-memory state, so it must be
        // rebuilt from the DB. Likewise a wiped projection, a re-ingested (mutated) existing
        // source, or a first load with no prior state force a full refold. Only when every read
        // this pass purely ADDED events onto an intact prior state is incremental projection sound.
        val sourceRemoved = previous.any { it.path !in files }
        val incremental = currentState != null && !projectionCleared &&
            !ingest.reingestedExisting && !sourceRemoved

        val state = when {
            ingest.read == 0 && currentState != null -> currentState
            incremental -> currentState.also { current -> ingest.newEvents.forEach(current::apply) }
            else -> loadState()
        }
        // Persist the projection version + observed maxHlc mid-pass so a crash before the
        // coordinator's markReady() still records progress. Terminal states (ready/failed) are
        // written by markReady()/fail(); this transient marker is not observed by any caller.
        dao.upsertState(projectionState(MIGRATION_RECONCILING, state.maxHlc))
        return MetadataReconciliation(
            state = state,
            sourcesRead = ingest.read,
            newEvents = if (incremental) ingest.newEvents else emptyList(),
            incremental = incremental && ingest.read > 0,
        )
    }

    suspend fun markReady(maxHlc: Hlc) {
        dao.upsertState(projectionState(MIGRATION_READY, maxHlc))
    }

    suspend fun persistAllocator(physicalMillis: Long, counter: Int) {
        val current = dao.state() ?: projectionState(MIGRATION_RECONCILING, Hlc.ZERO)
        dao.upsertState(current.copy(allocatorPhysical = physicalMillis, allocatorCounter = counter))
    }

    private suspend fun cleanOwnedStaging(entries: List<FileMeta>) {
        if (stagingCleaned) return
        stagingCleaned = true
        val marker = installationId?.let { ".$it." } ?: return
        val cutoff = systemNowMillis() - STAGING_MAX_AGE_MS
        entries.asSequence()
            .filter { NpnPaths.journalPath(it.relPath) is NpnPaths.JournalPath.Staging }
            .filter { it.name.contains(marker) }
            .filter { it.mtimeMillis in 1 until cutoff }
            .forEach { runCatching { fs.delete(it.relPath) } }
    }

    private suspend fun validateStorageVersions(entries: List<FileMeta>) {
        val supportedStoreVersions = setOf(NpnPaths.STORAGE_V2, NpnPaths.SNAPSHOT_V2)
        val unsupported = entries.asSequence()
            .mapNotNull { NpnPaths.objectStoreVersion(it.relPath) }
            .firstOrNull { it !in supportedStoreVersions }
        if (unsupported != null) fail("Unsupported metadata object store version $unsupported")
        val invalidObject = entries.firstOrNull { entry ->
            !entry.isDirectory && NpnPaths.objectStoreVersion(entry.relPath) in supportedStoreVersions &&
                NpnPaths.journalPath(entry.relPath) is NpnPaths.JournalPath.Unknown
        }
        if (invalidObject != null) fail("Invalid metadata object path: ${invalidObject.relPath}")
    }

    private suspend fun validatePublishedObjects(
        previous: List<MetadataSourceEntity>,
        files: Map<String, FileMeta>,
    ) {
        val current = dao.allSources()
        val supersededHashes = dao.appliedSupersededHashes().toHashSet()
        val stillMissing = HashSet<String>()
        val hardMissing = previous.firstOrNull { source ->
            if (source.path in files) return@firstOrNull false
            when (source.storageVersion) {
                NpnPaths.STORAGE_V2, NpnPaths.STORAGE_SNAPSHOT ->
                    isUnexplainedObjectLoss(source, current, files, supersededHashes, stillMissing)
                else -> !hasLegacyReplacement(source, current, files)
            }
        }
        // Drop pending counters for paths that reappeared or got explained this pass.
        pendingSupersession.keys.retainAll(stillMissing)
        if (hardMissing != null) {
            fail("Metadata source disappeared without a validated replacement: ${hardMissing.path}")
        }
    }

    /**
     * True when a missing content-addressed source is UNEXPLAINED and its grace window is spent.
     *
     * A loss is explained (returns false) when either a hash-identical replacement path is present,
     * or an applied snapshot lists the source's content hash as superseded. When neither holds yet
     * — a deletion that raced ahead of its snapshot — the source becomes "pending supersession":
     * its events are still applied locally, so we tolerate it for [PENDING_SUPERSESSION_LIMIT]
     * reconciles and only then treat it as real damage.
     */
    private fun isUnexplainedObjectLoss(
        source: MetadataSourceEntity,
        current: List<MetadataSourceEntity>,
        files: Map<String, FileMeta>,
        supersededHashes: Set<String>,
        stillMissing: MutableSet<String>,
    ): Boolean {
        val hasReplacement = current.any { candidate ->
            candidate.path in files && candidate.status == MetadataProjectionDao.STATUS_APPLIED &&
                candidate.contentHash == source.contentHash
        }
        if (hasReplacement) return false
        if (source.contentHash != null && source.contentHash in supersededHashes) return false
        // Pending supersession: not yet explained. Withhold the hard block for a grace window.
        stillMissing.add(source.path)
        val count = (pendingSupersession[source.path] ?: 0) + 1
        pendingSupersession[source.path] = count
        return count > PENDING_SUPERSESSION_LIMIT
    }

    private suspend fun hasLegacyReplacement(
        missing: MetadataSourceEntity,
        current: List<MetadataSourceEntity>,
        files: Map<String, FileMeta>,
    ): Boolean {
        val deviceDir = missing.path.substringBeforeLast('/')
        val required = dao.sourceEvents(missing.path).map { it.eventDeviceId to it.eventSequence }.toSet()
        val replacements = current.filter { source ->
            source.path in files && source.path.substringBeforeLast('/') == deviceDir &&
                source.status == MetadataProjectionDao.STATUS_APPLIED
        }
        val covered = replacements.flatMap { dao.sourceEvents(it.path) }
            .mapTo(HashSet()) { it.eventDeviceId to it.eventSequence }
        return required.isNotEmpty() && covered.containsAll(required)
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
        val sources = files.sortedBy { it.relPath }.filter { meta ->
            val path = NpnPaths.journalPath(meta.relPath)
            path !is NpnPaths.JournalPath.Staging && path !is NpnPaths.JournalPath.Unknown
        }
        for (meta in sources) ingestFile(meta, batch)
        return batch
    }

    private suspend fun ingestFile(meta: FileMeta, batch: IngestBatch) {
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
        val loaded = when (val prepared = prepareSource(meta.relPath, meta, existing)) {
            PreparedSource.Unchanged -> return
            is PreparedSource.Loaded -> prepared
        }
        val storageVersion = when (path) {
            is NpnPaths.JournalPath.SnapshotV2 -> NpnPaths.STORAGE_SNAPSHOT
            is NpnPaths.JournalPath.ObjectV2 -> NpnPaths.STORAGE_V2
            else -> NpnPaths.STORAGE_V1
        }
        val result = readSource(meta.relPath, path, loaded.bytes)
        if (result is JournalSourceRead.Retryable && alreadyApplied) {
            fail("Previously applied metadata source is no longer valid: ${meta.relPath} (${result.message})")
        }
        if (alreadyApplied) batch.reingestedExisting = true
        val supersedes = if (path is NpnPaths.JournalPath.SnapshotV2 && result is JournalSourceRead.Accepted) {
            SnapshotV2Reader.readSnapshot(meta.relPath, loaded.bytes)?.manifest?.supersedes.orEmpty()
        } else {
            null
        }
        applyReadResult(meta.relPath, storageVersion, loaded.fingerprint, loaded.contentHash, result, batch, supersedes)
    }

    private fun readSource(relPath: String, path: NpnPaths.JournalPath, bytes: ByteArray): JournalSourceRead =
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
                val diagnostic = result.diagnostics.joinToString("\n") { item ->
                    item.line?.let { "line $it: ${item.message}" } ?: item.message
                }.ifEmpty { null }
                val hashCol = contentHash.takeIf { storageVersion != NpnPaths.STORAGE_V1 }
                val source = MetadataSourceEntity(
                    path, storageVersion, fingerprint, hashCol,
                    MetadataProjectionDao.STATUS_APPLIED, diagnostic,
                )
                val unseen = result.events.filter { dao.event(it.event.dev, it.event.seq) == null }
                if (snapshotSupersedes != null) {
                    dao.ingestSnapshot(source, result.events.map(::eventEntity), snapshotSupersedes)
                } else {
                    val actor = result.events.firstOrNull()?.event?.dev
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
                        path, storageVersion, fingerprint, contentHash,
                        MetadataProjectionDao.STATUS_RETRYABLE, result.message,
                    ),
                )
                fail(result.message)
            }
            is JournalSourceRead.Unsupported -> fail(result.message)
        }
    }

    suspend fun recordLocalSource(
        path: String,
        storageVersion: Int,
        contentHash: String?,
        events: List<dev.npn.core.journal.JournalEvent>,
    ) {
        val canonical = events.map { event ->
            EventDecoderRegistry.decode(JournalCodec.encode(event), allowMissingV1 = false)
        }
        val bytes = JournalCodec.encodeLines(events).encodeToByteArray()
        val hash = contentHash ?: sha256Bytes(bytes)
        val meta = fs.stat(path)
        val source = MetadataSourceEntity(
            path = path,
            storageVersion = storageVersion,
            fingerprint = "${meta?.size ?: bytes.size.toLong()},${meta?.mtimeMillis ?: 0L}:$hash",
            contentHash = contentHash,
            status = MetadataProjectionDao.STATUS_APPLIED,
        )
        val actor = if (storageVersion == NpnPaths.STORAGE_V2) {
            MetadataTransactionActorEntity(events.first().dev, checkNotNull(contentHash))
        } else {
            null
        }
        dao.ingestSource(source, canonical.map(::eventEntity), actor)
    }

    suspend fun loadState(): FoldedState = FoldedState().also { state ->
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

    private suspend fun projectionState(status: String, maxHlc: Hlc, diagnostic: String? = null):
        MetadataProjectionStateEntity {
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
        const val PROJECTION_VERSION = 1
        private const val STAGING_MAX_AGE_MS = 60L * 60L * 1000L

        /**
         * How many consecutive reconciles a deleted-but-still-applied content-addressed source may
         * stay unexplained (no hash replacement, no applied snapshot superseding it) before it is
         * treated as real damage and hard-blocks writes. The 15-minute reconcile cadence makes this
         * roughly a two-hour grace window for a snapshot to arrive after its deletions.
         */
        const val PENDING_SUPERSESSION_LIMIT = 8

        /** Transient marker while a reconcile is in flight; not observed by any caller. */
        const val MIGRATION_RECONCILING = "reconciling"

        /** Terminal: reconcile completed and the coordinator marked the projection ready. */
        const val MIGRATION_READY = "ready-v2"

        /** Terminal: reconcile failed; writes are latched off by the coordinator. */
        const val MIGRATION_FAILED = "failed"
    }
}
