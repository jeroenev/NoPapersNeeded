package dev.npn.core.journal

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.VerifiedPublicationCapability
import dev.npn.core.fs.randomUuidV4
import dev.npn.core.fs.sha256Bytes

data class PublishedTransaction(
    val relPath: String,
    val contentHash: String,
    val bytes: ByteArray,
    val events: List<JournalEvent>,
)

class ContentAddressedJournalWriter(
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
        val events = ops.mapIndexed { index, pending ->
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
        val canonicalPath = NpnPaths.journalObjectPath(hash)
        val stagingPath = NpnPaths.journalStagingPath(hash, installationId, randomUuidV4())
        fs.writeBytesOnce(stagingPath, bytes)
        try {
            fs.publishVerifiedObjectOnce(stagingPath, canonicalPath, hash)
        } catch (failure: Throwable) {
            runCatching { fs.delete(stagingPath) }
            throw failure
        }
        return PublishedTransaction(canonicalPath, hash, bytes, events)
    }
}
