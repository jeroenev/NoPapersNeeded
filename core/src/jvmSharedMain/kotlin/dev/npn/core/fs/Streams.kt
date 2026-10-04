package dev.npn.core.fs

import java.io.InputStream
import java.security.MessageDigest

/** Buffer for streaming a file's bytes, so a copy or a hash never holds a whole PDF in memory. */
internal const val STREAM_BUFFER_SIZE = 64 * 1024

/** SHA-256 of the rest of this stream, lowercase hex. The caller closes the stream. */
internal fun InputStream.sha256Hex(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(STREAM_BUFFER_SIZE)
    var read = read(buffer)
    while (read >= 0) {
        digest.update(buffer, 0, read)
        read = read(buffer)
    }
    return digest.digest().toHexLower()
}
