package dev.npn.core.security

import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/** The JVM's PBKDF2, shared by Android and desktop. */
internal actual fun pbkdf2Sha256(
    secret: String,
    salt: ByteArray,
    iterations: Int,
    outputBytes: Int,
): ByteArray {
    val spec = PBEKeySpec(secret.toCharArray(), salt, iterations, outputBytes * 8)
    return try {
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
    } finally {
        spec.clearPassword()
    }
}

internal actual fun secureRandomBytes(size: Int): ByteArray = ByteArray(size).also { SecureRandom().nextBytes(it) }
