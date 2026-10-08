package dev.npn.core.journal

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.VerifiedPublicationCapability
import dev.npn.core.fs.holds
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.runCatchingCancellable
import kotlin.uuid.Uuid

data class PublishedTransaction(
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
 * [dev.npn.core.app.JournalCoordinator] is the only sanctioned writer, so its mutex +
 * [FoldedState] refresh + DB projection cannot be bypassed by an outside caller grabbing [commit]
 * directly. `core`'s test source sets remain in-module and may construct it.
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
 * failure rethrown, so a partial write never lingers; one whose object is in place regardless
 * published it. Shared by [ContentAddressedJournalWriter] (regular objects) and [SnapshotCompactor]
 * (snapshots); [snapshot] selects the bucket namespace. Returns the canonical object path.
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
    fs.writeBytesOnce(stagingPath, bytes)
    var published = false
    try {
        runCatchingCancellable { fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, hash) }.onFailure { failure ->
            // A failure after the object was installed (its staging file left behind, say) still
            // published it: readers take it, so the write must not report that it did not.
            if (!runCatchingCancellable { fs.holds(canonicalPath, hash) }.getOrDefault(false)) throw failure
        }
        published = true
    } finally {
        if (!published) runCatchingCancellable { fs.delete(stagingPath) }
    }
    return canonicalPath
}
