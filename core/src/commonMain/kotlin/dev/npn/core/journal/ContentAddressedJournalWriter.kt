package dev.npn.core.journal

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationCapability
import dev.npn.core.fs.randomUuidV4
import dev.npn.core.fs.sha256Bytes

data class PublishedTransaction(
    val relPath: String,
    val contentHash: String,
    val events: List<JournalEvent>,
)

/**
 * Publishes a journal transaction as a single write-once, content-addressed object (storage-v2).
 *
 * `internal` to the `core` module for the same reason as [SegmentWriter]: [JournalCoordinator]
 * is the only sanctioned write path, so its mutex + [FoldedState] refresh + DB projection cannot
 * be bypassed by an outside caller grabbing [commit] directly. `core`'s test source sets remain
 * in-module and may construct it.
 */
internal class ContentAddressedJournalWriter(
    private val fs: LibraryFileSystem,
    private val installationId: String,
    private val allocator: HlcWatermarkAllocator,
) {
    val supported: Boolean
        get() = fs.verifiedPublicationCapability == VerifiedPublicationCapability.ATOMIC_CREATE_NO_REPLACE

    fun observeHlc(remote: Hlc) = allocator.observe(remote)

    val watermark: Pair<Long, Int>
        get() = allocator.physicalMillis to allocator.counter

    suspend fun commit(ops: List<PendingOp>): PublishedTransaction? {
        if (ops.isEmpty()) return null
        check(supported) { "Storage provider does not support verified object publication" }
        val actor = randomUuidV4()
        val events =
            ops.mapIndexed { index, pending ->
                JournalEvent(
                    version = JournalEventVersions.CANONICAL,
                    seq = index + 1L,
                    hlc = allocator.next(actor),
                    dev = actor,
                    doc = pending.doc,
                    op = pending.op,
                )
            }
        val bytes = JournalCodec.encodeLines(events).encodeToByteArray()
        val hash = sha256Bytes(bytes)
        val canonicalPath = stageAndPublishVerifiedObject(fs, installationId, hash, bytes, snapshot = false)
        return PublishedTransaction(canonicalPath, hash, events)
    }
}

/**
 * Stage [bytes] under a per-installation `.part` name, then atomically publish them to the
 * content-addressed object path for [hash] (verified create-no-replace). On any publish failure the
 * staging file is best-effort deleted and the original failure rethrown, so a partial write never
 * lingers. Shared by [ContentAddressedJournalWriter] (regular objects) and [SnapshotCompactor]
 * (snapshots); [snapshot] selects the bucket namespace. Returns the canonical object path.
 */
internal suspend fun stageAndPublishVerifiedObject(
    fs: LibraryFileSystem,
    installationId: String,
    hash: String,
    bytes: ByteArray,
    snapshot: Boolean,
): String {
    val canonicalPath = NpnPaths.journalObjectPath(hash, snapshot = snapshot)
    val stagingPath = NpnPaths.journalStagingPath(hash, installationId, randomUuidV4(), snapshot = snapshot)
    fs.writeBytesOnce(stagingPath, bytes)
    try {
        fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, hash)
    } catch (failure: Throwable) {
        runCatching { fs.delete(stagingPath) }
        throw failure
    }
    return canonicalPath
}
