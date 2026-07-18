package dev.npn.core.fs

import java.security.MessageDigest
import java.util.UUID

actual fun sha256Bytes(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHexLower()

actual fun randomUuidV4(): String = UUID.randomUUID().toString()
