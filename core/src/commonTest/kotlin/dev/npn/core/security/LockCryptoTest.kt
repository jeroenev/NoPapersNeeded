package dev.npn.core.security

import dev.npn.core.settings.SettingsCodec
import dev.npn.core.settings.SettingsSnapshot
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LockCryptoTest {
    private val salt = ByteArray(LockCrypto.SALT_BYTES) { (it * 3 + 1).toByte() }

    @Test
    fun theHashVerifiesItsOwnSecretAndRejectsOthers() {
        val credential = LockCrypto.create("1234", iterations = LockCrypto.MIN_ITERATIONS, salt = salt)

        assertTrue(LockCrypto.verify("1234", credential))
        assertFalse(LockCrypto.verify("1235", credential))
        assertFalse(LockCrypto.verify("", credential))
        assertFalse(LockCrypto.verify("1234 ", credential))
    }

    @Test
    fun theSameSecretWithAnotherSaltHashesDifferently() {
        val first = LockCrypto.create("1234", iterations = LockCrypto.MIN_ITERATIONS, salt = salt)
        val second =
            LockCrypto.create(
                "1234",
                iterations = LockCrypto.MIN_ITERATIONS,
                salt = ByteArray(LockCrypto.SALT_BYTES) { 9 },
            )

        (first.hashHex == second.hashHex) shouldBe false
        assertTrue(LockCrypto.verify("1234", first))
        assertTrue(LockCrypto.verify("1234", second))
    }

    @Test
    fun aMalformedOrImpossibleCredentialIsRejectedRatherThanThrown() {
        val good = LockCrypto.create("1234", iterations = LockCrypto.MIN_ITERATIONS, salt = salt)

        assertFalse(LockCrypto.verify("1234", good.copy(scheme = "md5")))
        assertFalse(LockCrypto.verify("1234", good.copy(saltHex = "zz")))
        assertFalse(LockCrypto.verify("1234", good.copy(hashHex = "00")))
        assertFalse(LockCrypto.verify("1234", good.copy(iterations = 1)))
        assertFalse(LockCrypto.verify("1234", good.copy(iterations = LockCrypto.MAX_ITERATIONS + 1)))
    }

    @Test
    fun theCredentialRoundTripsThroughItsSettingsValue() {
        val credential = LockCrypto.create("1234", iterations = LockCrypto.MIN_ITERATIONS, salt = salt)

        LockCredential.decode(credential.encode()) shouldBe credential
        LockCredential.decode(null) shouldBe null
        LockCredential.decode("not a credential") shouldBe null
        LockCredential.decode("pbkdf2-sha256:notanumber:00:00") shouldBe null
    }

    @Test
    fun theSettingsFileHoldsTheHashButNeverTheSecret() {
        val credential = LockCrypto.create("swordfish", iterations = LockCrypto.MIN_ITERATIONS, salt = salt)
        val stored = mutableMapOf<String, String>()

        SettingsCodec.store(
            object : SettingsCodec.Writer {
                override fun putString(
                    key: String,
                    value: String,
                ) {
                    stored[key] = value
                }

                override fun putInt(
                    key: String,
                    value: Int,
                ) {
                    stored[key] = value.toString()
                }

                override fun putBoolean(
                    key: String,
                    value: Boolean,
                ) {
                    stored[key] = value.toString()
                }

                override fun remove(key: String) {
                    stored.remove(key)
                }
            },
            SettingsSnapshot(
                installationId = "test",
                appLock = AppLockSettings(enabled = true, credential = credential),
            ),
        )

        val serialised = stored.values.joinToString("\n")
        serialised shouldNotContain "swordfish"
        serialised.contains(credential.hashHex) shouldBe true
        serialised.contains(credential.saltHex) shouldBe true
    }

    @Test
    fun aStoredCredentialComesBackAndOpensTheLock() {
        val credential = LockCrypto.create("1234", iterations = LockCrypto.MIN_ITERATIONS, salt = salt)
        val stored = mutableMapOf<String, String>()
        val reader =
            object : SettingsCodec.Reader {
                override fun string(key: String): String? = stored[key]

                override fun int(key: String): Int? = stored[key]?.toIntOrNull()

                override fun boolean(key: String): Boolean? = stored[key]?.toBooleanStrictOrNull()
            }
        val writer =
            object : SettingsCodec.Writer {
                override fun putString(
                    key: String,
                    value: String,
                ) {
                    stored[key] = value
                }

                override fun putInt(
                    key: String,
                    value: Int,
                ) {
                    stored[key] = value.toString()
                }

                override fun putBoolean(
                    key: String,
                    value: Boolean,
                ) {
                    stored[key] = value.toString()
                }

                override fun remove(key: String) {
                    stored.remove(key)
                }
            }

        SettingsCodec.store(
            writer,
            SettingsSnapshot(
                installationId = "test",
                appLock = AppLockSettings(enabled = true, timeout = LockTimeout.FIVE_MINUTES, credential = credential),
            ),
        )

        val loaded = SettingsCodec.load(reader, "test")
        loaded.appLock.enabled shouldBe true
        loaded.appLock.timeout shouldBe LockTimeout.FIVE_MINUTES
        loaded.appLock.credential shouldBe credential
        assertTrue(LockCrypto.verify("1234", loaded.appLock.credential!!))
    }

    @Test
    fun anUnreadableStoredHashDisablesTheLock() {
        val stored = mutableMapOf("appLockEnabled" to "true", "appLockCredential" to "pbkdf2-sha256:1:zz:zz")
        val reader =
            object : SettingsCodec.Reader {
                override fun string(key: String): String? = stored[key]

                override fun int(key: String): Int? = stored[key]?.toIntOrNull()

                override fun boolean(key: String): Boolean? = stored[key]?.toBooleanStrictOrNull()
            }

        val loaded = SettingsCodec.load(reader, "test")

        loaded.appLock.enabled shouldBe false
        loaded.appLock.credential shouldBe null
    }
}
