package dev.npn.core.journal

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class JournalPathsTest {
    @Test
    fun classifiesVersionedJournalPaths() {
        val hash = "ab" + "0".repeat(62)
        val canonical = JournalPaths.objectPath(hash)
        canonical shouldBe ".npn/journal/objects-v2-ab/$hash.jsonl"
        JournalPaths.classify(canonical) shouldBe JournalPath.ObjectV2(hash)
        JournalPaths.classify(".npn/journal/objects-v3-ab/$hash.jsonl") shouldBe
            JournalPath.UnsupportedObjectStore(3)
        // A storage-v1 segment is no source this build reads; the reconcile passes over it like any unknown file.
        JournalPaths.classify(".npn/journal/dev1/0001.jsonl") shouldBe JournalPath.Unknown
    }

    @Test
    fun classifiesSnapshotBucketDistinctlyFromObjectsAndIsOldReaderSafe() {
        val hash = "cd" + "0".repeat(62)
        val snap = JournalPaths.objectPath(hash, snapshot = true)
        snap shouldBe ".npn/journal/objects-snap-v2-cd/$hash.jsonl"
        JournalPaths.classify(snap) shouldBe JournalPath.SnapshotV2(hash)
        // A distinct object-store version is surfaced (blocks writes) rather than silently ignored.
        JournalPaths.classify(".npn/journal/objects-snap-v3-cd/$hash.jsonl") shouldBe
            JournalPath.UnsupportedObjectStore(3)
        // objectStoreVersion recognizes both regular and snapshot buckets.
        JournalPaths.objectStoreVersion(snap) shouldBe JournalPaths.SNAPSHOT_V2
    }

    /** A file seen on disk under a name the library cannot address is no journal file, and never throws. */
    @Test
    fun anUnsafeObservedPathIsUnknownAndInNoBucket() {
        val hash = "ab" + "0".repeat(62)
        for (path in listOf(".npn/journal/objects-v2-ab/x\\$hash.jsonl", "C:x.jsonl", ".npn/journal/../$hash.jsonl")) {
            JournalPaths.classify(path) shouldBe JournalPath.Unknown
            JournalPaths.objectStoreVersion(path) shouldBe null
        }
    }

    /**
     * What a sync tool writes beside an object while it transfers it (Syncthing, Nextcloud and
     * ownCloud, Resilio) is neither an object nor a reason to fail; a name nothing knows still is.
     */
    @Test
    fun aSyncToolsTransferFileInABucketIsNoObjectAndNoError() {
        val hash = "ab" + "0".repeat(62)
        for (bucket in listOf(".npn/journal/objects-v2-ab", ".npn/journal/objects-snap-v2-ab")) {
            for (name in listOf(
                ".syncthing.$hash.jsonl.tmp",
                "~syncthing~$hash.jsonl.tmp",
                ".$hash.jsonl.~3fa9c2d1",
                "$hash.jsonl.!sync",
            )) {
                JournalPaths.classify("$bucket/$name") shouldBe JournalPath.SyncTemp
            }
            JournalPaths.classify("$bucket/readme.txt") shouldBe JournalPath.Unknown
            JournalPaths.classify("$bucket/prefix-$hash.jsonl") shouldBe JournalPath.Unknown
        }
    }

    @Test
    fun recoversDigestFromRecognizedConflictCopyOnly() {
        val hash = "ab" + "0".repeat(62)
        JournalPaths.recoverObjectHash("$hash (conflicted copy laptop).jsonl") shouldBe hash
        JournalPaths.recoverObjectHash("prefix-$hash.jsonl") shouldBe null
    }
}
