package dev.npn.app

import dev.npn.app.platform.ForegroundImport
import dev.npn.app.platform.importIntoLibrary
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** A share or open-with import: one outcome, reported as soon as the import itself is done. */
class ForegroundImportTest {
    @Test
    fun aColdShareRestoresTheLibraryAndReturnsWithoutWaitingForItsFollowUpWork() =
        runTest {
            val env = FakeAppEnvironment(backgroundScope, savedLocation = "/lib")

            val result = env.importIntoLibrary { "imported" }

            result shouldBe ForegroundImport.Done("imported")
            env.opened shouldContainExactly listOf("/lib")
            // OCR and enrichment are left to the background drain the caller schedules.
            val library = env.sessions.getValue("/lib")
            library.work.ocrDrains shouldBe 0
            library.work.enrichmentDrains shouldBe 0
        }

    @Test
    fun anOpenLibraryIsUsedAsItIs() =
        runTest {
            val env = FakeAppEnvironment(backgroundScope, savedLocation = "/lib")
            env.openPersistedLibrary()

            env.importIntoLibrary { "imported" } shouldBe ForegroundImport.Done("imported")
            env.opened shouldContainExactly listOf("/lib")
        }

    @Test
    fun withNoLibrarySetUpThereIsNothingToImportInto() =
        runTest {
            var imported = false

            val result = FakeAppEnvironment(backgroundScope).importIntoLibrary { imported = true }

            result shouldBe ForegroundImport.NoLibrary
            imported shouldBe false
        }

    @Test
    fun aSavedLibraryThatWillNotOpenIsAFailureNotAMissingLibrary() =
        runTest {
            val broken = IllegalStateException("Library folder not found: /usb/lib")
            val env = FakeAppEnvironment(backgroundScope, savedLocation = "/usb/lib")
            env.failures["/usb/lib"] = broken

            env.importIntoLibrary { "imported" } shouldBe ForegroundImport.Failed(broken)
        }

    @Test
    fun anImportThatThrowsIsAFailure() =
        runTest {
            val closed = IllegalStateException("Library session is closed")
            val env = FakeAppEnvironment(backgroundScope, savedLocation = "/lib")

            env.importIntoLibrary { throw closed } shouldBe ForegroundImport.Failed(closed)
        }

    @Test
    fun cancellationIsNotReportedAsAFailure() =
        runTest {
            val env = FakeAppEnvironment(backgroundScope, savedLocation = "/lib")

            assertFailsWith<CancellationException> {
                env.importIntoLibrary { throw CancellationException("left") }
            }
        }
}
