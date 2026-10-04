package dev.npn.core.fs

/** SHA-256 of [bytes], encoded as lowercase hexadecimal. */
expect fun sha256Bytes(bytes: ByteArray): String
