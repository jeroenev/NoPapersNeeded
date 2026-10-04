package dev.npn.core.fs

import java.security.MessageDigest

actual fun sha256Bytes(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHexLower()
