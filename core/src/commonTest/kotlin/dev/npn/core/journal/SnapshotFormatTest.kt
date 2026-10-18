package dev.npn.core.journal

import dev.npn.core.fs.sha256Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SnapshotFormatTest {
    private val doc = "a".repeat(64)

    private fun event(
        dev: String,
        seq: Long,
        physical: Long,
    ) = event(JournalOp.TagAdd("t$seq"), doc, seq, physical, dev)

    private fun snapshotBytes(
        supersedes: List<String>,
        events: List<JournalEvent>,
    ): ByteArray = earlierBuildSnapshot(supersedes, events)

    @Test
    fun snapshotBucketIsIgnoredByOldObjectClassification() {
        // The snapshot bucket must route to a distinct SnapshotV2 kind, not ObjectV2, so a
        // pre-snapshot reader (which only knows objects-v2-xx) treats it as an unknown file.
        val hash = "ab" + "0".repeat(62)
        val snapPath = JournalPaths.objectPath(hash, snapshot = true)
        assertEquals(".npn/journal/objects-snap-v2-ab/$hash.jsonl", snapPath)
        assertIs<JournalPath.SnapshotV2>(JournalPaths.classify(snapPath))
        // The bucket name does not match the objects-v<N>-<xx> pattern an old build recognizes.
        assertTrue(!snapPath.substringAfterLast("journal/").startsWith("objects-v"))
    }

    @Test
    fun snapshotReaderAcceptsMultiActorEventsAndManifest() {
        val events = listOf(event("dev-a", 1, 1000), event("dev-b", 1, 2000))
        val supersedes = listOf("11".repeat(32), "22".repeat(32))
        val bytes = snapshotBytes(supersedes, events)
        val path = JournalPaths.objectPath(sha256Bytes(bytes), snapshot = true)

        val accepted = assertIs<JournalSourceRead.Accepted>(SnapshotV2Reader.read(path, bytes))
        assertEquals(events, accepted.events.map { it.event })
        assertEquals(supersedes.sorted(), accepted.manifest?.supersedes?.sorted())
    }

    @Test
    fun snapshotReaderChecksTheHashItIsHandedRatherThanHashingAgain() {
        val bytes = snapshotBytes(emptyList(), listOf(event("dev-a", 1, 1000)))
        val path = JournalPaths.objectPath(sha256Bytes(bytes), snapshot = true)

        // The bytes match their path; only the handed hash does not, so a reader that re-hashed
        // would accept them.
        assertIs<JournalSourceRead.Retryable>(SnapshotV2Reader.read(path, bytes, contentHash = "0".repeat(64)))
    }

    @Test
    fun snapshotReaderRejectsHashMismatchAsRetryable() {
        val bytes = snapshotBytes(emptyList(), listOf(event("dev-a", 1, 1000)))
        val wrongPath = JournalPaths.objectPath("0".repeat(64), snapshot = true)
        assertIs<JournalSourceRead.Retryable>(SnapshotV2Reader.read(wrongPath, bytes))
    }

    @Test
    fun snapshotReaderRejectsTornManifestAsRetryable() {
        val partial = "{".encodeToByteArray()
        val path = JournalPaths.objectPath(sha256Bytes(partial), snapshot = true)
        assertIs<JournalSourceRead.Retryable>(SnapshotV2Reader.read(path, partial))
    }

    @Test
    fun snapshotReaderRejectsUnknownManifestVersionAsUnsupported() {
        val manifest = """{"snap":99,"supersedes":[]}"""
        val bytes = (manifest + "\n" + JournalCodec.encode(event("dev-a", 1, 1000))).encodeToByteArray()
        val path = JournalPaths.objectPath(sha256Bytes(bytes), snapshot = true)
        assertIs<JournalSourceRead.Unsupported>(SnapshotV2Reader.read(path, bytes))
    }

    @Test
    fun oldObjectReaderNeverCorruptsOnASnapshotBucketPath() {
        // A pre-snapshot StorageV2Reader would be asked about the snapshot path only if something
        // mis-routed it. It must fail soft (Unsupported/Retryable), never apply a partial fold.
        val events = listOf(event("dev-a", 1, 1000), event("dev-b", 1, 2000))
        val bytes = snapshotBytes(listOf("11".repeat(32)), events)
        val path = JournalPaths.objectPath(sha256Bytes(bytes), snapshot = true)
        val result = StorageV2Reader.read(path, bytes)
        assertIs<JournalSourceRead.Unsupported>(result)
        // And a snapshot reader asked about an object path refuses it rather than recovering a manifest.
        assertIs<JournalSourceRead.Unsupported>(
            SnapshotV2Reader.read(JournalPaths.objectPath(sha256Bytes(bytes)), bytes),
        )
    }
}
