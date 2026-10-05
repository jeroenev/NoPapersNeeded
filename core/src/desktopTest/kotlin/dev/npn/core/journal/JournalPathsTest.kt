package dev.npn.core.journal

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class JournalPathsTest {
    @Test
    fun classifiesVersionedJournalPathsWithoutTreatingObjectsAsLegacy() {
        val hash = "ab" + "0".repeat(62)
        val canonical = JournalPaths.objectPath(hash)
        canonical shouldBe ".npn/journal/objects-v2-ab/$hash.jsonl"
        JournalPaths.classify(canonical) shouldBe JournalPath.ObjectV2(hash, canonical = true)
        JournalPaths.classify(".npn/journal/objects-v3-ab/$hash.jsonl") shouldBe
            JournalPath.UnsupportedObjectStore(3)
        JournalPaths.classify(".npn/journal/dev1/0001.jsonl") shouldBe JournalPath.Legacy
    }

    @Test
    fun classifiesSnapshotBucketDistinctlyFromObjectsAndIsOldReaderSafe() {
        val hash = "cd" + "0".repeat(62)
        val snap = JournalPaths.objectPath(hash, snapshot = true)
        snap shouldBe ".npn/journal/objects-snap-v2-cd/$hash.jsonl"
        JournalPaths.classify(snap) shouldBe JournalPath.SnapshotV2(hash, canonical = true)
        // A distinct object-store version is surfaced (blocks writes) rather than silently ignored.
        JournalPaths.classify(".npn/journal/objects-snap-v3-cd/$hash.jsonl") shouldBe
            JournalPath.UnsupportedObjectStore(3)
        // objectStoreVersion recognizes both regular and snapshot buckets.
        JournalPaths.objectStoreVersion(snap) shouldBe JournalPaths.SNAPSHOT_V2
    }

    @Test
    fun recoversDigestFromRecognizedConflictCopyOnly() {
        val hash = "ab" + "0".repeat(62)
        JournalPaths.recoverObjectHash("$hash (conflicted copy laptop).jsonl") shouldBe hash
        JournalPaths.recoverObjectHash("prefix-$hash.jsonl") shouldBe null
    }
}
