package dev.npn.app.di

import dev.npn.app.FakeSettingsRepository
import dev.npn.core.app.AppDirs
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.SessionSettings
import dev.npn.core.app.jvmSessionDeps
import dev.npn.core.util.runCatchingCancellable
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFails

class DefaultAppEnvironmentTest {
    private val root: Path = Files.createTempDirectory("npn-env")
    private val settings = FakeSettingsRepository()

    /** Every location the factory built pieces for, with those pieces, in order. */
    private val built = mutableListOf<Pair<String, SessionDeps>>()

    /** Builds real per-library pieces, the way the desktop factory does, under [root]. */
    private val factory: suspend (String, CoroutineScope) -> SessionDeps = { location, scope ->
        val data = root.resolve("data").resolve(Path.of(location).fileName.toString()).toString()
        jvmSessionDeps(
            libraryRoot = Path.of(location),
            dirs = AppDirs(dbDir = data, thumbsDir = "$data/thumbs", tmpDir = "$data/tmp"),
            scope = scope,
            settings = SessionSettings(ocrLanguage = { "eng" }),
            installationId = "test-installation",
        ).also { built += location to it }
    }

    private val env = DefaultAppEnvironment(settings, watchedFolder = { null }, factory = factory)

    /** The locations whose pieces have had their databases closed, in the order they were built. */
    private suspend fun closed(): List<String> = built.filter { (_, deps) -> deps.isClosed() }.map { it.first }

    /** A closed Room database refuses every query. */
    private suspend fun SessionDeps.isClosed(): Boolean =
        runCatchingCancellable { db.metadataProjectionDao().state() }.isFailure &&
            runCatchingCancellable { deviceDb.keyValueDao().get("probe") }.isFailure

    @AfterTest
    fun teardown() {
        env.appScope.cancel()
        root.toFile().deleteRecursively()
    }

    private fun library(name: String): String = Files.createDirectories(root.resolve(name)).toString()

    /** A library whose journal holds a store from a newer build, which refuses to open. */
    private fun unreadableLibrary(name: String): String =
        library(name).also { Files.createDirectories(Path.of(it, ".npn/journal/objects-v9-00")) }

    @Test
    fun aFailedSwitchReleasesWhatItOpenedAndReturnsToThePreviousLibrary() =
        runTest {
            val good = library("good")
            val bad = unreadableLibrary("bad")
            try {
                env.openLibrary(good)

                assertFails { env.openLibrary(bad) }

                // The previous library closed for the switch, then the failed one's databases; the
                // previous library is open again, on pieces of its own.
                built.map { it.first } shouldContainExactly listOf(good, bad, good)
                closed() shouldContainExactly listOf(good, bad)
                env.session.value.shouldNotBeNull()
                env.opening.value shouldBe false
                settings.settingsFlow.value
                    ?.libraryLocation shouldBe good
            } finally {
                env.closeLibrary()
            }
        }

    @Test
    fun aFactoryThatThrowsLeavesTheOpenLibraryInPlace() =
        runTest {
            val good = library("good")
            val missing = root.resolve("unplugged").toString()
            val flaky =
                DefaultAppEnvironment(settings, watchedFolder = { null }) { location, scope ->
                    if (location == missing) throw IOException("No such folder: $location")
                    factory(location, scope)
                }
            try {
                flaky.openLibrary(good)

                assertFails { flaky.openLibrary(missing) }

                flaky.session.value.shouldNotBeNull()
                settings.settingsFlow.value
                    ?.libraryLocation shouldBe good
            } finally {
                flaky.closeLibrary()
                flaky.appScope.cancel()
            }
        }

    /**
     * A switch is done only once the new location is saved: one that cannot be saved closes the
     * library it opened and goes back to the previous one, which the saved location still names.
     */
    @Test
    fun aSwitchWhoseLocationCannotBeSavedGoesBackToThePreviousLibrary() =
        runTest {
            val good = library("good")
            val other = library("other")
            try {
                env.openLibrary(good)
                settings.failEdit = { throw IOException("Read-only file system") }

                assertFails { env.openLibrary(other) }

                built.map { it.first } shouldContainExactly listOf(good, other, good)
                closed() shouldContainExactly listOf(good, other)
                env.session.value.shouldNotBeNull()
                env.opening.value shouldBe false
                settings.settingsFlow.value
                    ?.libraryLocation shouldBe good
            } finally {
                env.closeLibrary()
            }
        }

    /** Opening the saved library writes nothing, so a configuration that cannot be written still opens it. */
    @Test
    fun theSavedLibraryOpensWhenSettingsCannotBeWritten() =
        runTest {
            val good = library("good")
            settings.updateSettings { it.copy(libraryLocation = good) }
            settings.failEdit = { throw IOException("Read-only file system") }
            try {
                env.openPersistedLibrary()

                env.session.value.shouldNotBeNull()
                closed() shouldBe emptyList()
            } finally {
                env.closeLibrary()
            }
        }

    @Test
    fun aFailedFirstOpenHasNothingToGoBackTo() =
        runTest {
            val bad = unreadableLibrary("bad")

            assertFails { env.openLibrary(bad) }

            closed() shouldContainExactly listOf(bad)
            env.session.value.shouldBeNull()
        }
}
