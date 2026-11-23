package dev.npn.core.pdf

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * Read this stream into at most [maxBytes], or null when it holds more. Embedded attachments are
 * untrusted: a declared size can lie or be absent, so the cap is enforced while reading rather
 * than from a stream's metadata.
 */
internal fun InputStream.readCapped(maxBytes: Int): ByteArray? {
    val out = ByteArrayOutputStream()
    val buffer = ByteArray(8 * 1024)
    var total = 0
    while (true) {
        val read = read(buffer)
        if (read < 0) return out.toByteArray()
        total += read
        if (total > maxBytes) return null
        out.write(buffer, 0, read)
    }
}
