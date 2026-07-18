package dev.npn.core.xmp

/**
 * BOM-aware decoding of a raw XMP metadata packet into a string.
 *
 * XMP packets are almost always UTF-8, but the spec allows UTF-16 (and UTF-32) and a leading
 * byte-order mark. PDFBox hands us the raw stream bytes as-is, so decoding them unconditionally
 * as UTF-8 mangles a UTF-16 packet and, even for UTF-8, can leave a stray U+FEFF at the front
 * that [XmpParser] would then choke on when matching the opening tag.
 *
 * This helper:
 * - detects a UTF-16 BE/LE BOM and decodes the remaining bytes accordingly,
 * - detects a UTF-8 BOM and decodes the remaining bytes as UTF-8,
 * - otherwise decodes as UTF-8,
 * - and always strips a single leading U+FEFF from the decoded text so the parser never sees it.
 *
 * Kept pure-Kotlin over [ByteArray] (no platform charset) so both JVM actuals share one code
 * path and behaviour is identical across platforms. UTF-32 is not decoded (extremely rare for
 * XMP); such packets fall through to the UTF-8 path, matching prior behaviour.
 */
internal fun decodeXmpBytes(bytes: ByteArray): String {
    val decoded = when {
        hasUtf16BeBom(bytes) -> decodeUtf16(bytes, startIndex = 2, bigEndian = true)
        hasUtf16LeBom(bytes) -> decodeUtf16(bytes, startIndex = 2, bigEndian = false)
        hasUtf8Bom(bytes) -> bytes.decodeToString(startIndex = 3)
        else -> bytes.decodeToString()
    }
    // A UTF-8 packet may still carry an encoded BOM (U+FEFF) or one may survive from a source
    // without a byte-level BOM; strip a single leading occurrence so the parser never sees it.
    return decoded.removePrefix("\uFEFF")
}

private fun hasUtf8Bom(bytes: ByteArray): Boolean =
    bytes.size >= 3 &&
        bytes[0] == 0xEF.toByte() &&
        bytes[1] == 0xBB.toByte() &&
        bytes[2] == 0xBF.toByte()

private fun hasUtf16BeBom(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()

private fun hasUtf16LeBom(bytes: ByteArray): Boolean =
    bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()

/**
 * Decode UTF-16 code units into a string. Surrogate pairs are preserved verbatim (each 16-bit
 * unit becomes a [Char]), so valid surrogate pairs reassemble into their supplementary code
 * points via Kotlin's UTF-16 [String] representation.
 */
private fun decodeUtf16(bytes: ByteArray, startIndex: Int, bigEndian: Boolean): String {
    val sb = StringBuilder((bytes.size - startIndex) / 2)
    var i = startIndex
    while (i + 1 < bytes.size) {
        val hi = bytes[i].toInt() and 0xFF
        val lo = bytes[i + 1].toInt() and 0xFF
        val unit = if (bigEndian) (hi shl 8) or lo else (lo shl 8) or hi
        sb.append(unit.toChar())
        i += 2
    }
    return sb.toString()
}
