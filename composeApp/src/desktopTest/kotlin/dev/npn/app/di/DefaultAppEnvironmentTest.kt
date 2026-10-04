package dev.npn.app.di

import dev.npn.app.FakeSettingsRepository
import dev.npn.core.app.AppDirs
import dev.npn.core.app.jvmSessionDeps
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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

    /** Locations whose pieces were closed, in order. */
    private val closed = mutableListOf<String>()

    /** Builds real per-library pieces, the way the desktop factory does, under [root]. */
    private val factory =
        PlatformLibraryFactory { location, scope ->
            val data = root.resolve("data").resolve(Path.of(location).fileName.toString()).toString()
            val deps =
                jvmSessionDeps(
                    libraryRoot = Path.of(location),
                    dirs = AppDirs(dbDir = data, thumbsDir = "$data/thumbs", tmpDir = "$data/tmp"),
                    scope = scope,
                    ocrLanguage = { "eng" },
                    trashRetentionDays = { 30 },
                    installationId = "test-installation",
                )
            OpenedLibrary(deps) {
                closed += location
                deps.closeDb()
            }
        }

    private val env = DefaultAppEnvironment(settings, factory = factory)

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

                // The previous library closed for the switch, then the failed one's database.
                closed shouldContainExactly listOf(good, bad)
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
                DefaultAppEnvironment(settings) { location, scope ->
                    if (location == missing) throw IOException("No such folder: $location")
                    factory.build(location, scope)
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

    @Test
    fun aFailedFirstOpenHasNothingToGoBackTo() =
        runTest {
            val bad = unreadableLibrary("bad")

            assertFails { env.openLibrary(bad) }

            closed shouldContainExactly listOf(bad)
            env.session.value.shouldBeNull()
        }
}
