package dev.npn.core.index

import dev.npn.core.db.MetadataEventEntity
import dev.npn.core.db.MetadataProjectionDao
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.journal.CanonicalJournalEvent
import dev.npn.core.journal.CompactionCandidate
import dev.npn.core.journal.CompactionSource
import dev.npn.core.journal.EventDecoderRegistry
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
     * It stats the snapshot first and records it in one transaction of its own.
     */
    suspend fun recordLocalSnapshot(published: PublishedSnapshot) {
        val meta = fs.stat(published.relPath)
        // Re-encoded only for its size, when the stat failed.
        val size = meta?.size ?: encodedSize(published)
        val canonical = published.events.map(EventDecoderRegistry::canonical)
        val source =
            MetadataSourceEntity(
                path = published.relPath,
                kind = SourceKind.SNAPSHOT,
                fingerprint = SourceFingerprint(size, meta?.mtimeMillis ?: 0L, published.contentHash),
                contentHash = published.contentHash,
                status = SourceStatus.APPLIED,
            )
        dao.ingest(source, canonical.map(eventEntity), supersedes = published.supersedes)
    }

    /**
     * The live content-addressed sources, as (v2 objects, snapshots), for planning a compaction:
     * applied, and not superseded by an applied snapshot (whose compaction deleted their files).
     * Read from the source tables alone, with the mtime each source's fingerprint recorded when it
     * was applied: deciding whether to compact touches neither the event table nor the filesystem.
     */
    suspend fun compactionSources(): Pair<List<CompactionSource>, List<CompactionSource>> {
        val superseded = dao.appliedSupersededHashes().toHashSet()
        val live =
            dao.allSources().filter { source ->
                source.status == SourceStatus.APPLIED && source.contentHash !in superseded
            }

        fun ofKind(kind: SourceKind): List<CompactionSource> =
            live.filter { it.kind == kind }.mapNotNull { source ->
                val hash = source.contentHash ?: return@mapNotNull null
                source.fingerprint.mtimeMillis?.let { CompactionSource(source.path, hash, it) }
            }
        return ofKind(SourceKind.OBJECT) to ofKind(SourceKind.SNAPSHOT)
    }

    /**
     * The sources a plan [chose], with their events, loaded in one join. Null when one of them no
     * longer is what its source row recorded (the file is gone, or its mtime moved): the next
     * metadata reconcile brings the table up to date, and a later tick plans again.
     */
    suspend fun candidates(chosen: List<CompactionSource>): List<CompactionCandidate>? {
        for (source in chosen) {
            if (fs.stat(source.relPath)?.mtimeMillis != source.mtimeMillis) return null
        }
        val events =
            dao.eventsOfSources(chosen.map { it.relPath }).groupBy({ it.sourcePath }) { stored ->
                EventDecoderRegistry.decodeStored(stored.canonicalJson)
            }
        return chosen.map {
            CompactionCandidate(
                it.relPath,
                it.contentHash,
                events[it.relPath].orEmpty(),
                it.mtimeMillis,
            )
        }
    }

    private fun encodedSize(published: PublishedSnapshot): Long =
        SnapshotCodec
            .encodeSnapshot(
                SnapshotManifest(SnapshotCodec.SNAPSHOT_MANIFEST_VERSION, published.supersedes),
                published.events,
            ).size
            .toLong()

    /** Best-effort delete of superseded object/snapshot files after their snapshot is applied. */
    suspend fun deleteSupersededFiles(paths: Collection<String>) {
        for (path in paths) runCatchingCancellable { fs.delete(path) }
    }
}
