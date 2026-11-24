package dev.npn.core.mail

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test

/**
 * Round trip through the real secret service. Skipped where there is none (CI): libsecret then
 * refuses the store, which is the typed failure the store already reports.
 */
class LibsecretMailCredentialStoreTest {
    @Test
    fun storedPasswordLoadsBackAndClears() =
        runTest {
            val store = LibsecretMailCredentialStore()
            assumeTrue("libsecret is not installed", store.available)
            val key = "npn-test-${UUID.randomUUID()}"
            val stored = store.store(key, "s3cret-ü")
            assumeTrue("no secret service answers on this host", stored is MailSecretResult.Success)
            try {
                store.load(key) shouldBe MailSecretResult.Success("s3cret-ü")
            } finally {
                store.clear(key)
            }
            store.load(key) shouldBe MailSecretResult.Success(null)
        }
}
