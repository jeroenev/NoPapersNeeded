package dev.npn.core.fs

private const val HEX_DIGITS = "0123456789abcdef"

/** Lowercase hex encoding of these bytes (used for content-hash filenames). */
internal fun ByteArray.toHexLower(): String {
    val hex = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        hex[i * 2] = HEX_DIGITS[v ushr 4]
        hex[i * 2 + 1] = HEX_DIGITS[v and 0x0F]
    }
    return hex.concatToString()
}
