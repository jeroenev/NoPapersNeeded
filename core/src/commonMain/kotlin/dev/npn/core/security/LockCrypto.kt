package dev.npn.core.security

/**
 * The one way an app-lock secret becomes, and is checked against, a stored hash.
 *
 * PBKDF2-HMAC-SHA256 with a random per-credential salt and a deliberately slow iteration count:
 * the library folder itself is never encrypted, so this is access control at the app's door, not
 * at rest. A settings file holds only [LockCredential], never the secret.
 */
object LockCrypto {
    const val SCHEME = "pbkdf2-sha256"
    const val DEFAULT_ITERATIONS = 600_000
    const val MIN_ITERATIONS = 10_000
    const val MAX_ITERATIONS = 2_000_000
    const val SALT_BYTES = 16
    const val HASH_BYTES = 32

    /** A fresh salt and hash for [secret]. Tests may pass a fixed [salt] and low [iterations]. */
    fun create(
        secret: String,
        iterations: Int = DEFAULT_ITERATIONS,
        salt: ByteArray = secureRandomBytes(SALT_BYTES),
    ): LockCredential {
        require(secret.isNotEmpty()) { "The app-lock secret must not be empty" }
        require(iterations in MIN_ITERATIONS..MAX_ITERATIONS) { "Unsupported PBKDF2 iteration count: $iterations" }
        require(salt.isNotEmpty()) { "The app-lock salt must not be empty" }
        val hash = pbkdf2Sha256(secret, salt, iterations, HASH_BYTES)
        return LockCredential(
            scheme = SCHEME,
            iterations = iterations,
            saltHex = salt.toHex(),
            hashHex = hash.toHex(),
        )
    }

    /**
     * Whether [secret] hashes to [credential]. Malformed input is a rejection, never an exception;
     * the comparison is constant-time so a wrong secret learns nothing from how far it got.
     */
    fun verify(
        secret: String,
        credential: LockCredential,
    ): Boolean {
        if (!credential.isValid()) return false
        val salt = credential.saltHex.hexToBytesOrNull() ?: return false
        val expected = credential.hashHex.hexToBytesOrNull() ?: return false
        val actual =
            runCatching { pbkdf2Sha256(secret, salt, credential.iterations, expected.size) }
                .getOrNull() ?: return false
        return constantTimeEquals(actual, expected)
    }

    private fun constantTimeEquals(
        left: ByteArray,
        right: ByteArray,
    ): Boolean {
        if (left.size != right.size) return false
        var difference = 0
        for (index in left.indices) difference = difference or (left[index].toInt() xor right[index].toInt())
        return difference == 0
    }
}

private const val HEX_DIGITS = "0123456789abcdef"

internal fun ByteArray.toHex(): String =
    buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xFF
            append(HEX_DIGITS[value ushr 4])
            append(HEX_DIGITS[value and 0x0F])
        }
    }

internal fun String.hexToBytesOrNull(): ByteArray? {
    if (length % 2 != 0) return null
    val bytes = ByteArray(length / 2)
    for (index in bytes.indices) {
        val high = this[index * 2].digitValue() ?: return null
        val low = this[index * 2 + 1].digitValue() ?: return null
        bytes[index] = ((high shl 4) or low).toByte()
    }
    return bytes
}

private fun Char.digitValue(): Int? =
    when (this) {
        in '0'..'9' -> this - '0'
        in 'a'..'f' -> this - 'a' + 10
        in 'A'..'F' -> this - 'A' + 10
        else -> null
    }

/** PBKDF2-HMAC-SHA256, in the JVM's own crypto implementation. */
internal expect fun pbkdf2Sha256(
    secret: String,
    salt: ByteArray,
    iterations: Int,
    outputBytes: Int,
): ByteArray

/** Salt material from the platform's cryptographically secure source. */
internal expect fun secureRandomBytes(size: Int): ByteArray
