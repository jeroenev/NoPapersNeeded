package dev.npn.core.journal

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.VerifiedPublicationCapability
import dev.npn.core.fs.sha256Bytes

/**
 * One object considered for compaction: its content hash, the events it contributes, and its mtime.
 */
data class CompactionCandidate(
    val relPath: String,
    val contentHash: String,
    val events: List<JournalEvent>,
    val mtimeMillis: Long,
)

/** A published snapshot: its path, hash, the hashes it supersedes, and the surviving events. */
data class PublishedSnapshot(
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
class SnapshotCompactor(
    private val fs: LibraryFileSystem,
    private val installationId: String,
) {
    val supported: Boolean
        get() = fs.verifiedPublicationCapability == VerifiedPublicationCapability.ATOMIC_CREATE_NO_REPLACE

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
        objects: List<CompactionCandidate>,
        snapshots: List<CompactionCandidate>,
        threshold: Int,
        nowMillis: Long,
        stabilityWindowMs: Long,
    ): List<CompactionCandidate>? {
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
        return out.sortedWith(compareBy({ it.hlc.physicalMillis }, { it.hlc.counter }, { it.hlc.deviceId }))
    }

    /** Encode + verify-publish one snapshot superseding [candidates]. Returns null for empty input. */
    suspend fun publish(candidates: List<CompactionCandidate>): PublishedSnapshot? {
        if (candidates.isEmpty()) return null
        check(supported) { "Storage provider does not support verified object publication" }
        val events = survivingEvents(candidates)
        val supersedes = candidates.map { it.contentHash }.sorted()
        val manifest = SnapshotManifest(SnapshotCodec.SNAPSHOT_MANIFEST_VERSION, supersedes)
        val bytes = SnapshotCodec.encodeSnapshot(manifest, events)
        val hash = sha256Bytes(bytes)
        // Never let a snapshot supersede itself (its own hash cannot be an input, but guard anyway).
        val canonicalPath = stageAndPublishVerifiedObject(fs, installationId, hash, bytes, snapshot = true)
        return PublishedSnapshot(canonicalPath, hash, supersedes, events)
    }
}
