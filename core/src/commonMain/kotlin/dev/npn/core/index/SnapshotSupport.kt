package dev.npn.core.index

import dev.npn.core.db.MetadataEventEntity
import dev.npn.core.db.MetadataProjectionDao
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.CanonicalJournalEvent
import dev.npn.core.journal.CompactionCandidate
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.JournalCodec
import dev.npn.core.journal.PublishedSnapshot
import dev.npn.core.journal.SnapshotCodec
import dev.npn.core.journal.SnapshotManifest
import dev.npn.core.util.runCatchingCancellable

/**
 * Compaction-side bookkeeping for the metadata accumulator: recording a locally-published snapshot,
 * gathering compaction candidates from applied sources, and deleting superseded files. Split from
 * [MetadataAccumulator] to keep that class focused on ingestion/validation.
 */
class SnapshotSupport(
    private val fs: LibraryFileSystem,
    private val dao: MetadataProjectionDao,
    private val eventEntity: (CanonicalJournalEvent) -> MetadataEventEntity,
) {
    /**
     * Record a locally-published compaction snapshot as an applied source with its supersession
     * manifest, so the just-written snapshot is folded/tracked without waiting for a reconcile read.
     */
    suspend fun recordLocalSnapshot(published: PublishedSnapshot) {
        val bytes = SnapshotCodec.encodeSnapshot(
            SnapshotManifest(SnapshotCodec.SNAPSHOT_MANIFEST_VERSION, published.supersedes),
            published.events,
        )
        val meta = fs.stat(published.relPath)
        val canonical = published.events.map { event ->
            EventDecoderRegistry.decode(JournalCodec.encode(event), allowMissingV1 = false)
        }
        val source = MetadataSourceEntity(
            path = published.relPath,
            storageVersion = NpnPaths.STORAGE_SNAPSHOT,
            fingerprint = "${meta?.size ?: bytes.size.toLong()},${meta?.mtimeMillis ?: 0L}:${published.contentHash}",
            contentHash = published.contentHash,
            status = MetadataProjectionDao.STATUS_APPLIED,
        )
        dao.ingestSnapshot(source, canonical.map(eventEntity), published.supersedes)
    }

    /**
     * Gather compaction candidates from currently-applied sources. Returns (v2 objects, snapshots).
     * Only APPLIED content-addressed sources whose file still exists qualify.
     */
    suspend fun compactionCandidates(): Pair<List<CompactionCandidate>, List<CompactionCandidate>> {
        val objects = ArrayList<CompactionCandidate>()
        val snapshots = ArrayList<CompactionCandidate>()
        for (source in dao.allSources()) {
            val candidate = candidateFor(source) ?: continue
            when (source.storageVersion) {
                NpnPaths.STORAGE_SNAPSHOT -> snapshots.add(candidate)
                NpnPaths.STORAGE_V2 -> objects.add(candidate)
            }
        }
        return objects to snapshots
    }

    private suspend fun candidateFor(source: MetadataSourceEntity): CompactionCandidate? {
        if (source.status != MetadataProjectionDao.STATUS_APPLIED) return null
        val hash = source.contentHash ?: return null
        val meta = fs.stat(source.path) ?: return null
        val events = dao.sourceEvents(source.path)
            .mapNotNull { dao.event(it.eventDeviceId, it.eventSequence) }
            .map { EventDecoderRegistry.decode(it.canonicalJson, allowMissingV1 = false).event }
        return CompactionCandidate(source.path, hash, events, meta.mtimeMillis)
    }

    /** Best-effort delete of superseded object/snapshot files after their snapshot is applied. */
    suspend fun deleteSupersededFiles(paths: Collection<String>) {
        for (path in paths) runCatchingCancellable { fs.delete(path) }
    }
}
