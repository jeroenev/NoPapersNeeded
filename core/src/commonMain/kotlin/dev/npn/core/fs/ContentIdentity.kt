package dev.npn.core.fs

/** SHA-256 of [bytes], encoded as lowercase hexadecimal. */
expect fun sha256Bytes(bytes: ByteArray): String

/** An incremental SHA-256, for content read in chunks. */
expect class Sha256Digest() {
    fun update(
        bytes: ByteArray,
        offset: Int,
        length: Int,
    )

    /** The digest of everything given so far; the instance starts over afterwards. */
    fun digest(): ByteArray
}
