package dev.npn.core.sync

import dev.npn.core.fs.Sha256Digest
import dev.npn.core.fs.sha256Bytes
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DropboxContentHashTest {
    private val block = DropboxContentHash.BLOCK_SIZE

    @Test
    fun anEmptyFileHashesNoBlocks() {
        DropboxContentHash().hex() shouldBe sha256Bytes(ByteArray(0))
    }

    @Test
    fun aFileIsTheHashOfItsBlocksHashesHoweverItIsRead() {
        val bytes = ByteArray(2 * block + 5) { (it * 31 + 7).toByte() }
        val expected =
            Sha256Digest()
                .apply {
                    listOf(0 until block, block until 2 * block, 2 * block until bytes.size).forEach { range ->
                        val digest = Sha256Digest().apply { update(bytes, range.first, range.count()) }.digest()
                        update(digest, 0, digest.size)
                    }
                }.digest()
                .joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

        DropboxContentHash().apply { update(bytes, bytes.size) }.hex() shouldBe expected
        // Chunks that straddle the block boundaries, as a streamed read hands them over.
        val chunked = DropboxContentHash()
        var offset = 0
        while (offset < bytes.size) {
            val chunk = bytes.copyOfRange(offset, minOf(bytes.size, offset + 1_000_003))
            chunked.update(chunk, chunk.size)
            offset += chunk.size
        }
        chunked.hex() shouldBe expected
    }

    @Test
    fun aFileOfExactlyOneBlockHashesOneBlock() {
        val bytes = ByteArray(block) { it.toByte() }
        val blockDigest = Sha256Digest().apply { update(bytes, 0, bytes.size) }.digest()
        DropboxContentHash().apply { update(bytes, bytes.size) }.hex() shouldBe sha256Bytes(blockDigest)
    }
}
