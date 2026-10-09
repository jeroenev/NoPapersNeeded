package dev.npn.core.journal

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.NpnPaths.JOURNAL_DIR
import dev.npn.core.fs.NpnPaths.Temp
import dev.npn.core.fs.SHA256_HEX

/** What a file under [JOURNAL_DIR] is, as [JournalPaths.classify] tells it from its path alone. */
sealed interface JournalPath {
    /** A v1 segment, `.npn/journal/<deviceId>/<seq>.jsonl`: still read, never written. */
    data object Legacy : JournalPath

    data class ObjectV2(
        val expectedHash: String,
        val canonical: Boolean,
    ) : JournalPath

    /** A compaction snapshot object (multi-actor events + a supersession manifest). */
    data class SnapshotV2(
        val expectedHash: String,
        val canonical: Boolean,
    ) : JournalPath

    data class UnsupportedObjectStore(
        val version: Int,
    ) : JournalPath

    data object Staging : JournalPath

    data object Unknown : JournalPath
}

/**
 * The journal's object-store layout under [JOURNAL_DIR]: where an object or snapshot is published
 * and staged, and what an observed file is. Pure string work on library-relative paths, like
 * [NpnPaths].
 */
object JournalPaths {
    /** Object-store version of the `objects-v2-<xx>` buckets this build reads and writes. */
    const val STORAGE_V2 = 2

    /** Snapshot object-store version. Independent axis from [STORAGE_V2]; currently the same value. */
    const val SNAPSHOT_V2 = 2

    private val objectBucketPattern = Regex("^objects-v([1-9][0-9]*)-([0-9a-fA-F]{2})$")

    // Snapshots use a DISTINCT bucket namespace so pre-snapshot readers classify them as Unknown
    // (ignored) rather than as an object they must fold or an unsupported store that blocks writes.
    private val snapshotBucketPattern = Regex("^objects-snap-v([1-9][0-9]*)-([0-9a-fA-F]{2})$")

    private val objectNamePattern = Regex("^([0-9a-f]{64})\\.jsonl$")
    private val embeddedHashPattern = Regex("(?:^|[^0-9a-f])([0-9a-f]{64})(?=[^0-9a-f]|$)")
    private val objectStagingPattern = Regex("^\\.[0-9a-f]{64}\\.[^.]+\\.[^.]+\\.part$")
    private val legacySegmentPattern = Regex("^[0-9]+(?:-[0-9]+\\.snap)?\\.jsonl$")

    /** `.npn/journal/objects-v2-<xx>/<hash>.jsonl`, or the snapshot bucket's when [snapshot]. */
    fun objectPath(
        hash: String,
        snapshot: Boolean = false,
    ): String {
        require(SHA256_HEX.matches(hash)) { "Invalid SHA-256: $hash" }
        val infix = if (snapshot) "objects-snap-v$SNAPSHOT_V2" else "objects-v$STORAGE_V2"
        return "$JOURNAL_DIR/$infix-${hash.take(2)}/$hash.jsonl"
    }

    /** Where [installationId] stages [hash] before publishing it: `.<hash>.<installationId>.<nonce>.part` beside it. */
    fun stagingPath(
        hash: String,
        installationId: String,
        nonce: String,
        snapshot: Boolean = false,
    ): String = NpnPaths.parentDir(objectPath(hash, snapshot)) + "/.$hash.$installationId.$nonce.part"

    /**
     * Classify direct journal source files without letting object buckets masquerade as devices. A
     * path the library cannot address ([NpnPaths.isSafe]), as a listing or the watcher may report
     * one, is [JournalPath.Unknown] rather than a throw.
     */
    fun classify(relPath: String): JournalPath {
        if (!NpnPaths.isSafe(relPath)) return JournalPath.Unknown
        val norm = NpnPaths.normalize(relPath)
        val prefix = "$JOURNAL_DIR/"
        if (!norm.startsWith(prefix)) return JournalPath.Unknown
        val parts = norm.removePrefix(prefix).split('/')
        return if (parts.size == 2) classifyFile(parts[0], parts[1]) else JournalPath.Unknown
    }

    private fun classifyFile(
        dir: String,
        name: String,
    ): JournalPath {
        val snapshotBucket = snapshotBucketPattern.matchEntire(dir)
        val bucket = objectBucketPattern.matchEntire(dir)
        return when {
            snapshotBucket != null -> classifyBucketFile(snapshotBucket, name, SNAPSHOT_V2, snapshot = true)
            bucket != null -> classifyBucketFile(bucket, name, STORAGE_V2, snapshot = false)
            dir.isNotEmpty() && legacySegmentPattern.matches(name) -> JournalPath.Legacy
            else -> JournalPath.Unknown
        }
    }

    /**
     * Classify a file inside a matched object/snapshot bucket. [supported] is the store version
     * this reader understands ([STORAGE_V2] or [SNAPSHOT_V2]); [snapshot] selects the resulting
     * canonical path type. Object and snapshot buckets share identical structure, so a single
     * helper keeps the two in lockstep.
     */
    private fun classifyBucketFile(
        bucket: MatchResult,
        name: String,
        supported: Int,
        snapshot: Boolean,
    ): JournalPath {
        val version = bucket.groupValues[1].toIntOrNull() ?: Int.MAX_VALUE
        val expected = recoverObjectHash(name)
        val prefixMatches = expected?.startsWith(bucket.groupValues[2].lowercase()) == true
        return when {
            version != supported -> JournalPath.UnsupportedObjectStore(version)

            // The direct filesystem stages a write beside its target, so publishing an object's
            // staging file can leave one of its own in the bucket too.
            objectStagingPattern.matches(name) || Temp.kindOf(name) == Temp.Kind.WRITE -> JournalPath.Staging

            expected == null || !prefixMatches -> JournalPath.Unknown

            snapshot -> JournalPath.SnapshotV2(expected, objectNamePattern.matches(name))

            else -> JournalPath.ObjectV2(expected, objectNamePattern.matches(name))
        }
    }

    /** Recover a canonical digest from a canonical or common sync-conflict object filename. */
    fun recoverObjectHash(name: String): String? {
        objectNamePattern.matchEntire(name)?.let { return it.groupValues[1] }
        if (!NpnPaths.isSyncConflict(name)) return null
        val matches =
            embeddedHashPattern
                .findAll(name.lowercase())
                .map { it.groupValues[1] }
                .distinct()
                .toList()
        return matches.singleOrNull()
    }

    /**
     * The object-store version of the bucket [relPath] is in, or null outside any bucket, and for a
     * path the library cannot address ([NpnPaths.isSafe]).
     */
    fun objectStoreVersion(relPath: String): Int? {
        if (!NpnPaths.isSafe(relPath)) return null
        val norm = NpnPaths.normalize(relPath)
        if (!norm.startsWith("$JOURNAL_DIR/")) return null
        val dir = norm.removePrefix("$JOURNAL_DIR/").substringBefore('/')
        val version =
            objectBucketPattern.matchEntire(dir)?.groupValues?.get(1)
                ?: snapshotBucketPattern.matchEntire(dir)?.groupValues?.get(1)
                ?: return null
        return version.toIntOrNull() ?: Int.MAX_VALUE
    }
}
