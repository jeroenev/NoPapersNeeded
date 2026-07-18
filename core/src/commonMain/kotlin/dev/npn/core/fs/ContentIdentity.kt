package dev.npn.core.fs

/** SHA-256 of [bytes], encoded as lowercase hexadecimal. */
expect fun sha256Bytes(bytes: ByteArray): String

/** Cryptographically random RFC 4122 UUID version 4. */
expect fun randomUuidV4(): String
