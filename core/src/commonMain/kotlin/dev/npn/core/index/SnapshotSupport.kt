package dev.npn.core.index

import dev.npn.core.db.MetadataProjectionDao
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.journal.CompactionCandidate
import dev.npn.core.journal.CompactionSource
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.PublishedSnapshot
import dev.npn.core.journal.SnapshotCodec
import dev.npn.core.journal.SnapshotManifest
import dev.npn.core.util.runCatchingCancellable

/**
 * Compaction-side bookkeeping over the metadata accumulator's tables: recording a locally-published
 * snapshot, gathering compaction candidates from applied sources, and deleting superseded files.
 * [dev.npn.core.app.JournalCoordinator.compact] and [MetadataAccumulator]'s reconcile share one.
 */
class SnapshotSupport(
    private val fs: LibraryFileSystem,
    private val dao: MetadataProjectionDao,
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
        dao.ingest(source, canonical.map { it.toEventEntity() }, supersedes = published.supersedes)
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

    /**
     * Delete the applied objects and snapshots among [listed] whose events a snapshot also in
     * [listed] carries ([supersededByListed]): the compaction that published it could not delete
     * them, and a later plan leaves them out. Runs after the pass read [listed], so every snapshot
     * there reads as the one applied. [sources] is the source table before the pass. Returns the
     * paths deleted.
     */
    suspend fun deleteSupersededLeftovers(
        listed: Set<String>,
        sources: List<MetadataSourceEntity>,
    ): Set<String> {
        val superseded = dao.supersededByListed(listed, sources)
        if (superseded.isEmpty()) return emptySet()
        val leftovers =
            sources.filter { source ->
                source.status == SourceStatus.APPLIED && source.path in listed && source.contentHash in superseded
            }
        return leftovers
            .filter { runCatchingCancellable { fs.delete(it.path) }.isSuccess }
            .mapTo(HashSet()) { it.path }
    }
}

/**
 * The content hashes whose events an applied snapshot in [listed] carries: the ones it supersedes,
 * and in turn the ones each snapshot among those superseded. A snapshot that is gone vouches for
 * nothing, whatever its source row says: the folder is the truth, and the index only a cache of it.
 * [sources] maps a superseded snapshot's hash to its path; one gone from the folder was read in an
 * earlier pass, so the table holds it from before this one.
 */
internal suspend fun MetadataProjectionDao.supersededByListed(
    listed: Set<String>,
    sources: List<MetadataSourceEntity>,
): Set<String> {
    val bySnapshot = appliedSupersessions().groupBy({ it.snapshotPath }, { it.supersededHash })
    if (bySnapshot.isEmpty()) return emptySet()
    val snapshotWithHash =
        sources
            .filter { it.kind == SourceKind.SNAPSHOT }
            .mapNotNull { source -> source.contentHash?.let { it to source.path } }
            .toMap()
    val carried = HashSet<String>()
    val pending = ArrayDeque(bySnapshot.keys.filter { it in listed })
    while (pending.isNotEmpty()) {
        for (hash in bySnapshot[pending.removeFirst()].orEmpty()) {
            if (carried.add(hash)) snapshotWithHash[hash]?.let(pending::addLast)
        }
    }
    return carried
}
