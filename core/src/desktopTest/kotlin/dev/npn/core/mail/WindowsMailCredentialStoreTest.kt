package dev.npn.core.mail

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.util.UUID
import kotlin.test.Test

/**
 * Round trip through the real Windows Credential Manager. Runs on Windows only (the Windows CI job
 * runs these tests); elsewhere the store reports itself unavailable and the test is skipped.
 */
class WindowsMailCredentialStoreTest {
    @Test
    fun storedPasswordLoadsBackAndClears() =
        runTest {
            val store = WindowsMailCredentialStore()
            assumeTrue("not Windows", store.available)
            val key = "npn-test-${UUID.randomUUID()}"
            store.store(key, "s3cret-ü") shouldBe MailSecretResult.Success(Unit)
            try {
                store.load(key) shouldBe MailSecretResult.Success("s3cret-ü")
                // Saving again replaces the password rather than adding a second credential.
                store.store(key, "other") shouldBe MailSecretResult.Success(Unit)
                store.load(key) shouldBe MailSecretResult.Success("other")
            } finally {
                store.clear(key)
            }
            store.load(key) shouldBe MailSecretResult.Success(null)
            // Clearing what is not there is what clearing asked for.
            store.clear(key) shouldBe MailSecretResult.Success(Unit)
        }

    @Test
    fun elsewhereTheStoreIsUnavailableAndRefusesTyped() =
        runTest {
            val store = WindowsMailCredentialStore()
            assumeTrue("on Windows", !store.available)
            store.load("any") shouldBe MailSecretResult.Failed(MailSecretFailure.UNAVAILABLE)
        }
}
