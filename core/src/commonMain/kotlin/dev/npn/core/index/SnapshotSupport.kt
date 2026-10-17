package dev.npn.core.index

import dev.npn.core.db.MetadataProjectionDao
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.MetadataSupersededEntity
import dev.npn.core.db.SourceFingerprint
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.journal.CompactionCandidate
import dev.npn.core.journal.CompactionSource
import dev.npn.core.journal.EventCopies
import dev.npn.core.journal.EventDecoderRegistry
import dev.npn.core.journal.JournalSourceRead
import dev.npn.core.journal.PublishedSnapshot
import dev.npn.core.journal.SnapshotCodec
import dev.npn.core.journal.SnapshotManifest
import dev.npn.core.journal.SnapshotV2Reader
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
        return chosen.map { CompactionCandidate(it.relPath, it.contentHash, events[it.relPath].orEmpty()) }
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
     * The applied objects and snapshots among [listed] whose events a snapshot also in [listed]
     * carries: the compaction that published it could not delete them, and a later plan leaves them
     * out. A reconcile pass reads that snapshot rather than them, whatever their own bytes are now.
     * [sources] is the source table before the pass.
     *
     * A manifest is taken at its word only as far as the events go: a source is carried when every
     * event the index recorded for it is carried in full by the listed snapshots that supersede it,
     * directly or through a chain. The snapshot's bytes prove that: the event table keeps the richest
     * copy across sources, so its joins cannot prove which keys an older snapshot actually holds.
     * Leftovers are rare, and only a pass that has one loads any events.
     */
    suspend fun supersededLeftovers(
        listed: Set<String>,
        sources: List<MetadataSourceEntity>,
    ): Set<String> = leftovers(listed, sources).carried

    /**
     * Delete the [supersededLeftovers] among [listed]. Runs after the pass read [listed], so every
     * snapshot there reads as the one applied. [sources] is the source table before the pass.
     * Returns the paths deleted.
     *
     * The claims a listed snapshot makes on a leftover whose events it does not hold are dropped from
     * the index: that object is a live source again, whose loss the validator counts, and which the
     * next compaction carries.
     */
    suspend fun deleteSupersededLeftovers(
        listed: Set<String>,
        sources: List<MetadataSourceEntity>,
    ): Set<String> {
        val found = leftovers(listed, sources)
        for (claim in found.refuted) dao.deleteSupersession(claim.snapshotPath, claim.supersededHash)
        return found.carried.filterTo(HashSet()) { runCatchingCancellable { fs.delete(it) }.isSuccess }
    }

    /** The superseded sources [listed] holds: the [carried] ones' paths, and the [refuted] claims on the others. */
    private class Leftovers(
        val carried: Set<String>,
        val refuted: List<MetadataSupersededEntity>,
    )

    private suspend fun leftovers(
        listed: Set<String>,
        sources: List<MetadataSourceEntity>,
    ): Leftovers {
        val supersessions = dao.appliedSupersessions()
        val graph = SupersessionGraph(sources, supersessions)
        // What each listed snapshot carries, on its own.
        val reaches = graph.snapshots.filter { it in listed }.associateWith { graph.reach(listOf(it)) }
        val candidates =
            sources.filter { source ->
                source.status == SourceStatus.APPLIED && source.path in listed &&
                    reaches.values.any { source.contentHash in it.hashes }
            }
        if (candidates.isEmpty()) return Leftovers(emptySet(), emptyList())
        val eventsOf = HashMap<String, Map<EventKey, String>>()
        val requiredBySource = dao.eventsOfSources(candidates.map { it.path }).groupBy { it.sourcePath }
        val carried = HashSet<String>()
        val refuted = ArrayList<MetadataSupersededEntity>()
        for (candidate in candidates) {
            val carriers = reaches.filterValues { candidate.contentHash in it.hashes }
            val held = carriers.keys.map { eventsOf.getOrPut(it) { snapshotCopies(it) } }
            val required = requiredBySource[candidate.path].orEmpty()
            val fullyCarried =
                required.isNotEmpty() &&
                    required.all { stored ->
                        val event = EventDecoderRegistry.decodeStored(stored.canonicalJson)
                        held.any { copies ->
                            val copy = copies[event.dev to event.seq]
                            copy != null && EventCopies.richer(copy, stored.canonicalJson) == copy
                        }
                    }
            if (fullyCarried) {
                carried += candidate.path
            } else {
                val claimants = carriers.values.flatMapTo(HashSet()) { it.snapshots }
                refuted +=
                    supersessions.filter { it.supersededHash == candidate.contentHash && it.snapshotPath in claimants }
            }
        }
        return Leftovers(carried, refuted)
    }

    /** Canonical copies actually present in a verified snapshot, independent of the merged index. */
    private suspend fun snapshotCopies(path: String): Map<EventKey, String> {
        val read = runCatchingCancellable { SnapshotV2Reader.read(path, fs.readBytes(path)) }.getOrNull()
        return (read as? JournalSourceRead.Accepted)?.events.orEmpty().associate {
            (it.event.dev to it.event.seq) to it.canonicalJson
        }
    }
}

/** An event's identity in the index: its device and sequence. */
private typealias EventKey = Pair<String, Long>

/**
 * The applied snapshots' supersession claims, as a graph: a snapshot to the hashes it supersedes,
 * and through a hash that is itself a snapshot on to what that one superseded.
 */
private class SupersessionGraph(
    sources: List<MetadataSourceEntity>,
    supersessions: List<MetadataSupersededEntity>,
) {
    private val bySnapshot = supersessions.groupBy({ it.snapshotPath }, { it.supersededHash })

    /** [sources]' snapshots by content hash; a superseded snapshot gone from the folder is still among them. */
    private val snapshotWithHash =
        sources
            .filter { it.kind == SourceKind.SNAPSHOT }
            .mapNotNull { source -> source.contentHash?.let { it to source.path } }
            .toMap()

    /** The paths of the snapshots that claim anything. */
    val snapshots: Set<String> get() = bySnapshot.keys

    /** What [roots] carry: every hash they supersede, in turn, and every snapshot on the way, theirs included. */
    fun reach(roots: Collection<String>): Reach {
        val hashes = HashSet<String>()
        val snapshots = roots.filterTo(HashSet()) { it in bySnapshot }
        val pending = ArrayDeque(snapshots)
        while (pending.isNotEmpty()) {
            for (hash in bySnapshot[pending.removeFirst()].orEmpty()) {
                if (hashes.add(hash)) snapshotWithHash[hash]?.let { if (snapshots.add(it)) pending.addLast(it) }
            }
        }
        return Reach(hashes, snapshots)
    }

    class Reach(
        val hashes: Set<String>,
        val snapshots: Set<String>,
    )
}

/**
 * The content hashes whose events an applied snapshot in [listed] carries: the ones it supersedes,
 * and in turn the ones each snapshot among those superseded. A snapshot that is gone vouches for
 * nothing, whatever its source row says: the folder is the truth, and the index only a cache of it.
 * The graph maps a superseded snapshot's hash to its path; one gone from the folder was read in an
 * earlier pass, so the table holds it from before this one. A claim a pass found false is no longer
 * among the graph's ([SnapshotSupport.deleteSupersededLeftovers]).
 */
private fun SupersessionGraph.supersededByListed(listed: Set<String>): Set<String> =
    reach(snapshots.filter { it in listed }).hashes

/** What the journal files one reconcile pass listed and read hold; see [carriedByListed]. */
internal class ListedCarriage(
    /** The content hashes whose events are on disk in the listed files. */
    val hashes: Set<String>,
    /** The paths of the gone snapshots a restore undid: nothing supersedes them, and their inputs carry them. */
    val undone: List<String>,
)

/**
 * What the files in [listed] carry, for a pass that read them; [sources] is the source table after
 * the read. A source's events are on disk when the source is listed, when a listed snapshot carries
 * them ([supersededByListed]), or, for a snapshot that is gone, when every hash it superseded is
 * carried in turn: a snapshot holds the events of what it replaced and nothing else
 * ([dev.npn.core.journal.SnapshotCompactor.publish]), so a folder put back from before a compaction
 * lost none of them.
 *
 * Such a gone snapshot that no applied snapshot supersedes is [ListedCarriage.undone]: the index is
 * to forget it, as a rebuild from the folder would never have known it, or compaction keeps choosing
 * it and leaves out the objects it replaced although they are back. One that another gone snapshot
 * supersedes still explains that one, so it is forgotten only after it.
 */
internal suspend fun MetadataProjectionDao.carriedByListed(
    listed: Set<String>,
    sources: List<MetadataSourceEntity>,
): ListedCarriage {
    val supersessions = appliedSupersessions()
    val hashes = HashSet(SupersessionGraph(sources, supersessions).supersededByListed(listed))
    sources.filter { it.status == SourceStatus.APPLIED && it.path in listed }.mapNotNullTo(hashes) { it.contentHash }
    val inputs = supersessions.groupBy({ it.snapshotPath }, { it.supersededHash })
    // Gone snapshots by content hash.
    val gone =
        sources
            .filter { it.kind == SourceKind.SNAPSHOT && it.status == SourceStatus.APPLIED && it.path !in listed }
            .mapNotNull { source -> source.contentHash?.let { it to source.path } }
    do {
        val unpacked =
            gone.filter { (hash, path) ->
                val replaced = inputs[path].orEmpty()
                hash !in hashes && replaced.isNotEmpty() && hashes.containsAll(replaced)
            }
        unpacked.mapTo(hashes) { (hash, _) -> hash }
    } while (unpacked.isNotEmpty())
    val superseded = supersessions.mapTo(HashSet()) { it.supersededHash }
    val undone = gone.filter { (hash, _) -> hash in hashes && hash !in superseded }.map { (_, path) -> path }
    return ListedCarriage(hashes, undone)
}
