package dev.npn.core.sync

import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.Sha256Digest
import dev.npn.core.fs.toHexLower

/**
 * Dropbox's content hash (`content_hash`, rclone's `dropbox` hash type): the SHA-256 of the
 * concatenated SHA-256 digests of the file's 4 MiB blocks. Two files of one size with equal content
 * hashes have equal blocks, so equal bytes, as surely as equal SHA-256s say so: a match can name the
 * document behind a remote file without downloading it ([DocumentResolver]).
 */
class DropboxContentHash {
    private val block = Sha256Digest()
    private val overall = Sha256Digest()
    private var inBlock = 0

    fun update(
        bytes: ByteArray,
        length: Int,
    ) {
        var offset = 0
        while (offset < length) {
            val take = minOf(length - offset, BLOCK_SIZE - inBlock)
            block.update(bytes, offset, take)
            inBlock += take
            offset += take
            if (inBlock == BLOCK_SIZE) endBlock()
        }
    }

    fun hex(): String {
        if (inBlock > 0) endBlock()
        return overall.digest().toHexLower()
    }

    private fun endBlock() {
        val digest = block.digest()
        overall.update(digest, 0, digest.size)
        inBlock = 0
    }

    companion object {
        /** rclone's name for this hash type, as `operations/list` reports it. */
        const val HASH_TYPE = "dropbox"
        const val BLOCK_SIZE = 4 * 1024 * 1024
    }
}

/** The SHA-256 and the Dropbox content hash of the library file at [relPath], from one read of it. */
internal suspend fun LibraryFileSystem.sha256AndDropboxHash(relPath: String): Pair<String, String> {
    val sha256 = Sha256Digest()
    val dropbox = DropboxContentHash()
    readChunked(relPath) { bytes, length ->
        sha256.update(bytes, 0, length)
        dropbox.update(bytes, length)
    }
    return sha256.digest().toHexLower() to dropbox.hex()
}
