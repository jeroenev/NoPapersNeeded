package dev.npn.core.journal

import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.sha256Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SnapshotFormatTest {
    private val doc = "a".repeat(64)

    private fun event(
        dev: String,
        seq: Long,
        physical: Long,
    ) = JournalEvent(
        version = JournalEventVersions.V1,
        seq = seq,
        hlc = Hlc(physical, 0, dev),
        dev = dev,
        doc = doc,
        op = JournalOp.TagAdd("t$seq"),
    )

    private fun snapshotBytes(
        supersedes: List<String>,
        events: List<JournalEvent>,
    ): ByteArray =
        SnapshotCodec.encodeSnapshot(SnapshotManifest(SnapshotCodec.SNAPSHOT_MANIFEST_VERSION, supersedes), events)

    @Test
    fun snapshot_bucket_is_ignored_by_old_object_classification() {
        // The snapshot bucket must route to a distinct SnapshotV2 kind, not ObjectV2, so a
        // pre-snapshot reader (which only knows objects-v2-xx) treats it as an unknown file.
        val hash = "ab" + "0".repeat(62)
        val snapPath = NpnPaths.journalObjectPath(hash, snapshot = true)
        assertEquals(".npn/journal/objects-snap-v2-ab/$hash.jsonl", snapPath)
        assertIs<NpnPaths.JournalPath.SnapshotV2>(NpnPaths.journalPath(snapPath))
        // The bucket name does not match the objects-v<N>-<xx> pattern an old build recognizes.
        assertTrue(!snapPath.substringAfterLast("journal/").startsWith("objects-v"))
    }

    @Test
    fun snapshot_reader_accepts_multi_actor_events_and_manifest() {
        val events = listOf(event("dev-a", 1, 1000), event("dev-b", 1, 2000))
        val supersedes = listOf("11".repeat(32), "22".repeat(32))
        val bytes = snapshotBytes(supersedes, events)
        val path = NpnPaths.journalObjectPath(sha256Bytes(bytes), snapshot = true)

        val accepted = assertIs<JournalSourceRead.Accepted>(SnapshotV2Reader.read(path, bytes))
        assertEquals(events, accepted.events.map { it.event })

        val read = SnapshotV2Reader.readSnapshot(path, bytes)!!
        assertEquals(supersedes.sorted(), read.manifest.supersedes.sorted())
    }

    @Test
    fun snapshot_reader_rejects_hash_mismatch_as_retryable() {
        val bytes = snapshotBytes(emptyList(), listOf(event("dev-a", 1, 1000)))
        val wrongPath = NpnPaths.journalObjectPath("0".repeat(64), snapshot = true)
        assertIs<JournalSourceRead.Retryable>(SnapshotV2Reader.read(wrongPath, bytes))
    }

    @Test
    fun snapshot_reader_rejects_torn_manifest_as_retryable() {
        val partial = "{".encodeToByteArray()
        val path = NpnPaths.journalObjectPath(sha256Bytes(partial), snapshot = true)
        assertIs<JournalSourceRead.Retryable>(SnapshotV2Reader.read(path, partial))
    }

    @Test
    fun snapshot_reader_rejects_unknown_manifest_version_as_unsupported() {
        val manifest = """{"snap":99,"supersedes":[]}"""
        val bytes = (manifest + "\n" + JournalCodec.encode(event("dev-a", 1, 1000))).encodeToByteArray()
        val path = NpnPaths.journalObjectPath(sha256Bytes(bytes), snapshot = true)
        assertIs<JournalSourceRead.Unsupported>(SnapshotV2Reader.read(path, bytes))
    }

    @Test
    fun old_object_reader_never_corrupts_on_a_snapshot_bucket_path() {
        // A pre-snapshot StorageV2Reader would be asked about the snapshot path only if something
        // mis-routed it. It must fail soft (Unsupported/Retryable), never apply a partial fold.
        val events = listOf(event("dev-a", 1, 1000), event("dev-b", 1, 2000))
        val bytes = snapshotBytes(listOf("11".repeat(32)), events)
        val path = NpnPaths.journalObjectPath(sha256Bytes(bytes), snapshot = true)
        val result = StorageV2Reader.read(path, bytes)
        assertIs<JournalSourceRead.Unsupported>(result)
        // And the manifest recovery from an old reader that lacks snapshot support returns nothing.
        assertNull(SnapshotV2Reader.readSnapshot(NpnPaths.journalObjectPath(sha256Bytes(bytes)), bytes))
    }
}
