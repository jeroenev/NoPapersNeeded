package dev.npn.core.journal

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.sha256Bytes

/**
 * What planning a compaction knows of one applied object: where it is, its content hash, and its
 * mtime. No events: a plan is made from these alone, and only the objects it picks are loaded.
 */
internal data class CompactionSource(
    val relPath: String,
    val contentHash: String,
    val mtimeMillis: Long,
)

/** One object considered for compaction: its path, its content hash and the events it contributes. */
internal data class CompactionCandidate(
    val relPath: String,
    val contentHash: String,
    val events: List<JournalEvent>,
)

/** A published snapshot: its path, hash, the hashes it supersedes, and the surviving events. */
internal data class PublishedSnapshot(
    val relPath: String,
    val contentHash: String,
    val supersedes: List<String>,
    val events: List<JournalEvent>,
)

/**
 * Plans and publishes compaction snapshots for storage-v2 objects.
 *
 * A snapshot carries the FULL surviving event multiset of the objects it supersedes (deduped by
 * event identity, not folded) plus a manifest listing those objects' hashes. Publishing goes
 * through the same verified-publication path as a regular object. The caller (coordinator) is
 * responsible for applying + deleting under its single-writer mutex.
 */
internal class SnapshotCompactor(
    private val fs: LibraryFileSystem,
    private val installationId: String,
) {
    /**
     * Decide whether to compact. Returns the candidates to fold into one snapshot, or null when the
     * threshold is not exceeded or nothing is stable enough. [nowMillis] and [stabilityWindowMs]
     * gate on file mtime so we do not race an in-flight sync.
     *
     * Anti-ping-pong: compact only when the count of stable NON-snapshot candidates alone exceeds
     * [threshold], OR when at least two snapshots exist (chain compaction). A single fresh snapshot
     * plus a handful of new objects will not re-trigger until real growth resumes.
     */
    fun plan(
        objects: List<CompactionSource>,
        snapshots: List<CompactionSource>,
        threshold: Int,
        nowMillis: Long,
        stabilityWindowMs: Long,
    ): List<CompactionSource>? {
        val liveCount = objects.size + snapshots.size
        if (liveCount <= threshold && snapshots.size < 2) return null
        val cutoff = nowMillis - stabilityWindowMs
        val stableObjects = objects.filter { it.mtimeMillis in 1..cutoff }
        val stableSnapshots = snapshots.filter { it.mtimeMillis in 1..cutoff }
        val stable = stableObjects + stableSnapshots
        if (stable.size < 2) return null
        // Only proceed when growth is driven by real non-snapshot objects, or multiple snapshots
        // need collapsing. This prevents repeated reconciles from re-snapshotting a single snapshot.
        val triggered = stableObjects.size > threshold || stableSnapshots.size >= 2
        return if (triggered) stable else null
    }

    /** Surviving events = the union (by identity) of every candidate's events, in a stable order. */
    fun survivingEvents(candidates: List<CompactionCandidate>): List<JournalEvent> {
        val seen = HashSet<Dot>()
        val out = ArrayList<JournalEvent>()
        for (candidate in candidates.sortedBy { it.contentHash }) {
            for (event in candidate.events) {
                if (seen.add(event.dot)) out.add(event)
            }
        }
        return out.sortedBy { it.hlc }
    }

    /**
     * Encode + verify-publish one snapshot superseding [candidates], which a [plan] chose and so are
     * never empty.
     */
    suspend fun publish(candidates: List<CompactionCandidate>): PublishedSnapshot {
        require(candidates.isNotEmpty()) { "A snapshot supersedes at least one source" }
        val events = survivingEvents(candidates)
        val supersedes = candidates.map { it.contentHash }.sorted()
        val manifest = SnapshotManifest(SnapshotCodec.SNAPSHOT_MANIFEST_VERSION, supersedes)
        val bytes = SnapshotCodec.encodeSnapshot(manifest, events)
        val hash = sha256Bytes(bytes)
        val canonicalPath = stageAndPublishVerifiedObject(fs, installationId, hash, bytes, snapshot = true)
        return PublishedSnapshot(canonicalPath, hash, supersedes, events)
    }
}
