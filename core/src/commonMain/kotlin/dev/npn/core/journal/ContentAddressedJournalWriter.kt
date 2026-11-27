package dev.npn.core.journal

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.publishStagedOnce
import dev.npn.core.fs.sha256Bytes
import dev.npn.core.util.IdSource

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
    private val ids: IdSource = IdSource.Random,
    /**
     * Run with each object's hash before it is published: where this installation notes what it
     * wrote ([dev.npn.core.index.AuthoredObjects]), as no object says which device wrote it.
     */
    private val beforePublish: suspend (hash: String) -> Unit = {},
) {
    fun observeHlc(remote: Hlc) = allocator.observe(remote)

    val watermark: Pair<Long, Int>
        get() = allocator.physicalMillis to allocator.counter

    /**
     * Publish [ops], at least one, as one transaction object.
     *
     * With [beneathEveryEdit], the events carry the earliest clock there is (physical time 0), so
     * every last-writer-wins value they set loses to any write made anywhere else, whenever that
     * write's object arrives: for defaults a device writes before it has seen what the library
     * decided (the seeded fields). The fresh actor keeps them distinct from every other event.
     */
    suspend fun commit(
        ops: List<PendingOp>,
        beneathEveryEdit: Boolean = false,
    ): PublishedTransaction {
        require(ops.isNotEmpty()) { "A transaction publishes at least one event" }
        val actor = ids.next()
        val events =
            ops.mapIndexed { index, pending ->
                JournalEvent(
                    version = JournalEventVersions.CANONICAL,
                    seq = index + 1L,
                    hlc = if (beneathEveryEdit) Hlc(0L, index, actor) else allocator.next(actor),
                    dev = actor,
                    doc = pending.doc,
                    op = pending.op,
                )
            }
        val bytes = JournalCodec.encodeLines(events).encodeToByteArray()
        val hash = sha256Bytes(bytes)
        beforePublish(hash)
        val canonicalPath = stageAndPublishVerifiedObject(fs, installationId, hash, bytes, ids)
        return PublishedTransaction(canonicalPath, hash, events)
    }
}

/**
 * Stage [bytes] under a per-installation `.part` name, then atomically publish them to the
 * content-addressed object path for [hash] (verified create-no-replace). A publish that fails with
 * no object of those bytes in place has the staging file best-effort deleted and the original
 * failure rethrown, so a partial write never lingers; one whose object is in place regardless (its
 * staging file left behind, say) published it. Returns the canonical object path.
 */
internal suspend fun stageAndPublishVerifiedObject(
    fs: LibraryFileSystem,
    installationId: String,
    hash: String,
    bytes: ByteArray,
    ids: IdSource = IdSource.Random,
): String {
    val canonicalPath = JournalPaths.objectPath(hash)
    val stagingPath = JournalPaths.stagingPath(hash, installationId, ids.next())
    fs.publishStagedOnce(stagingPath, canonicalPath, hash, targetHeldCounts = true) {
        fs.writeBytesOnce(stagingPath, bytes)
    }
    return canonicalPath
}
