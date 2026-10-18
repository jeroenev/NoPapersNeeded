package dev.npn.core.journal

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.publishStagedOnce
import dev.npn.core.fs.sha256Bytes
import kotlin.uuid.Uuid

internal data class PublishedTransaction(
    val relPath: String,
    val contentHash: String,
    val events: List<JournalEvent>,
)

/**
 * Publishes a journal transaction as a single write-once, content-addressed object (storage-v2),
 * the only format this build writes. Every transaction gets a fresh actor id, so no two writers,
 * on this device or a clone of it, can ever produce the same event identity.
 *
 * `internal` to the `core` module so the single write path is enforced at the type level:
 * [dev.npn.core.index.JournalCoordinator] is the only sanctioned writer, so its mutex +
 * [FoldedState] refresh + DB projection cannot be bypassed by an outside caller grabbing [commit]
 * directly. `core`'s test source sets remain in-module and may construct it.
 */
internal class ContentAddressedJournalWriter(
    private val fs: LibraryFileSystem,
    private val installationId: String,
    private val allocator: HlcWatermarkAllocator,
) {
    fun observeHlc(remote: Hlc) = allocator.observe(remote)

    val watermark: Pair<Long, Int>
        get() = allocator.physicalMillis to allocator.counter

    /** Publish [ops], at least one, as one transaction object. */
    suspend fun commit(ops: List<PendingOp>): PublishedTransaction {
        require(ops.isNotEmpty()) { "A transaction publishes at least one event" }
        val actor = Uuid.random().toString()
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
 * content-addressed object path for [hash] (verified create-no-replace). A publish that fails with
 * no object of those bytes in place has the staging file best-effort deleted and the original
 * failure rethrown, so a partial write never lingers; one whose object is in place regardless (its
 * staging file left behind, say) published it. Shared by [ContentAddressedJournalWriter] (regular
 * objects) and [SnapshotCompactor] (snapshots); [snapshot] selects the bucket namespace. Returns
 * the canonical object path.
 */
internal suspend fun stageAndPublishVerifiedObject(
    fs: LibraryFileSystem,
    installationId: String,
    hash: String,
    bytes: ByteArray,
    snapshot: Boolean,
): String {
    val canonicalPath = JournalPaths.objectPath(hash, snapshot = snapshot)
    val stagingPath = JournalPaths.stagingPath(hash, installationId, Uuid.random().toString(), snapshot = snapshot)
    fs.publishStagedOnce(stagingPath, canonicalPath, hash, targetHeldCounts = true) {
        fs.writeBytesOnce(stagingPath, bytes)
    }
    return canonicalPath
}
