package dev.npn.core.fs

import java.security.MessageDigest

actual fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexLower()

actual class Sha256Digest actual constructor() {
    // Used by both actual members; the typed analysis runs without the compiler's expect/actual
    // matching and misreports it.
    @Suppress("UnusedPrivateProperty")
    private val digest = MessageDigest.getInstance("SHA-256")

    actual fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    ) = digest.update(bytes, offset, length)

    actual fun digest(): ByteArray = digest.digest()
}
