package dev.npn.core.index

import dev.npn.core.db.MetadataProjectionDao
import dev.npn.core.db.MetadataSourceEntity
import dev.npn.core.db.MetadataSupersededEntity
import dev.npn.core.db.SourceKind
import dev.npn.core.db.SourceStatus

/**
 * What the compaction snapshots earlier builds wrote account for, for [MetadataAccumulator]'s
 * reconcile. Those builds folded objects into a snapshot and deleted them; this build reads the
 * snapshots and writes none, so a library they compacted lacks objects the index once applied.
 */
internal class SnapshotSupport(
    private val dao: MetadataProjectionDao,
) {
    /**
     * What the files in [listed] carry, for a pass that read them; [sources] is the source table after
     * the read. A source's events are on disk when the source is listed, when a listed snapshot carries
     * them, or, for a snapshot that is gone, when every hash it superseded is carried in turn: a
     * snapshot holds the events of what it replaced and nothing else, so a folder put back from before
     * a compaction lost none of them.
     *
     * Such a gone snapshot that no applied snapshot supersedes is [ListedCarriage.undone]: the index is
     * to forget it, as a rebuild from the folder would never have known it. One that another gone
     * snapshot supersedes still explains that one, so it is forgotten only after it.
     */
    suspend fun carriedByListed(
        listed: Set<String>,
        sources: List<MetadataSourceEntity>,
    ): ListedCarriage {
        val supersessions = dao.appliedSupersessions()
        val graph = SupersessionGraph(sources, supersessions)
        // What a listed snapshot carries: the hashes it supersedes, and in turn those each superseded
        // snapshot among them superseded. A snapshot that is gone vouches for nothing, whatever its
        // source row says: the folder is the truth, and the index only a cache of it. The graph maps a
        // superseded snapshot's hash to its path; one gone from the folder was read in an earlier pass,
        // so the table holds it from before this one.
        val hashes = HashSet(graph.reach(graph.snapshots.filter { it in listed }))
        sources.filter { it.status == SourceStatus.APPLIED && it.path in listed }.mapNotNullTo(
            hashes,
        ) { it.contentHash }
        // Gone snapshots by content hash.
        val gone =
            sources
                .filter { it.kind == SourceKind.SNAPSHOT && it.status == SourceStatus.APPLIED && it.path !in listed }
                .mapNotNull { source -> source.contentHash?.let { it to source.path } }
        do {
            val unpacked =
                gone.filter { (hash, path) ->
                    val replaced = graph.inputsOf(path)
                    hash !in hashes && replaced.isNotEmpty() && hashes.containsAll(replaced)
                }
            unpacked.mapTo(hashes) { (hash, _) -> hash }
        } while (unpacked.isNotEmpty())
        val superseded = supersessions.mapTo(HashSet()) { it.supersededHash }
        val undone = gone.filter { (hash, _) -> hash in hashes && hash !in superseded }.map { (_, path) -> path }
        return ListedCarriage(hashes, undone)
    }
}

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

    /** The hashes the snapshot at [path] supersedes itself. */
    fun inputsOf(path: String): List<String> = bySnapshot[path].orEmpty()

    /** What the snapshots at [roots] carry: every hash they supersede, and in turn what each of those superseded. */
    fun reach(roots: Collection<String>): Set<String> {
        val hashes = HashSet<String>()
        val visited = roots.filterTo(HashSet()) { it in bySnapshot }
        val pending = ArrayDeque(visited)
        while (pending.isNotEmpty()) {
            for (hash in bySnapshot[pending.removeFirst()].orEmpty()) {
                if (hashes.add(hash)) snapshotWithHash[hash]?.let { if (visited.add(it)) pending.addLast(it) }
            }
        }
        return hashes
    }
}

/** What the journal files one reconcile pass listed and read hold; see [SnapshotSupport.carriedByListed]. */
internal class ListedCarriage(
    /** The content hashes whose events are on disk in the listed files. */
    val hashes: Set<String>,
    /** The paths of the gone snapshots a restore undid: nothing supersedes them, and their inputs carry them. */
    val undone: List<String>,
)
