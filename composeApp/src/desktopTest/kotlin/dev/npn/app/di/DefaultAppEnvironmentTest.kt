package dev.npn.app.di

import dev.npn.app.FakeSettingsRepository
import dev.npn.core.app.SessionDeps
import dev.npn.core.app.jvmSessionDeps
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.security.AppLockSettings
import dev.npn.core.security.LockAttempts
import dev.npn.core.security.LockCrypto
import dev.npn.core.security.UnlockResult
import dev.npn.core.settings.AppDirs
import dev.npn.core.settings.SessionSettings
import dev.npn.core.testing.copy
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.watch.WatchedFolder
import dev.npn.core.watch.WatchedFolderFile
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
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
            dirs = AppDirs(dbDir = data, thumbsDir = "$data/thumbs"),
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
    fun theAppLockKeepsCountingWrongSecretsAcrossARestart() =
        runTest {
            val credential =
                LockCrypto.create(
                    "2468",
                    iterations = LockCrypto.MIN_ITERATIONS,
                    salt = ByteArray(LockCrypto.SALT_BYTES),
                )
            val stored = FakeSettingsRepository()
            stored.updateSettings {
                it.copy(
                    appLock = AppLockSettings(enabled = true, credential = credential),
                    appLockAttempts = LockAttempts(failures = 3),
                )
            }
            val restarted = DefaultAppEnvironment(stored, watchedFolder = { null }, factory = factory)
            try {
                restarted.lock.state.value.failedAttempts shouldBe 3

                // The fourth wrong secret in all, so the first that earns a wait.
                restarted.lock.unlockWithSecret("1357") shouldBe UnlockResult.WrongSecret

                stored.settings
                    .first { it.appLockAttempts.failures == 4 }
                    .appLockAttempts.retryAtMillis
                    .shouldNotBeNull()
                restarted.lock.state.value.retryAtMillis
                    .shouldNotBeNull()
            } finally {
                restarted.appScope.cancel()
            }
        }

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
                    .libraryLocation shouldBe good
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
                    .libraryLocation shouldBe good
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
                    .libraryLocation shouldBe good
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

    /**
     * A caller cancelled while the session closes (an Android viewmodel cleared mid-switch) still
     * has the whole close done: no closed session is left published as the open one.
     */
    @Test
    fun aCloseWhoseCallerIsCancelledStillClosesTheLibrary() =
        runTest {
            val good = library("good")
            // An import that holds the close up: the session waits out the work it owns.
            val entered = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holding =
                DefaultAppEnvironment(settings, watchedFolder = { null }) { location, scope ->
                    val real = factory(location, scope)
                    real.copy(
                        pdfEngine =
                            object : PdfEngine by real.pdfEngine {
                                override suspend fun imagesToPdf(images: List<ByteArray>): ByteArray {
                                    entered.complete(Unit)
                                    try {
                                        awaitCancellation()
                                    } finally {
                                        cancelled.complete(Unit)
                                        withContext(NonCancellable) { release.await() }
                                    }
                                }
                            },
                    )
                }
            try {
                holding.openLibrary(good)
                val session = holding.session.value.shouldNotBeNull()
                // The close fails it: a session closed under a call is an error for its caller.
                val import =
                    holding.appScope.launch {
                        runCatchingCancellable { session.ingest.importImagesAsPdf(listOf(byteArrayOf(1)), "scan") }
                    }
                entered.await()

                val closing = holding.appScope.launch { holding.closeLibrary() }
                // The close has cancelled the import and now waits for it to finish.
                cancelled.await()
                closing.cancel()
                release.complete(Unit)
                closing.join()
                import.join()

                holding.session.value.shouldBeNull()
                closed() shouldContainExactly listOf(good)
            } finally {
                release.complete(Unit)
                holding.appScope.cancel()
            }
        }

    /**
     * A folder watch that fails (the JVM's watch service out of inotify instances) is logged: it
     * ends neither the app nor the loop that starts an importer for each folder chosen after it.
     */
    @Test
    fun aFailedFolderWatchIsLoggedAndTheNextFolderIsStillSwept() =
        runTest {
            val good = library("good")
            val failed = CompletableDeferred<Unit>()
            val swept = CompletableDeferred<Unit>()
            val folders =
                mapOf(
                    "broken" to EmptyFolder(changes = flow { throw IOException("inotify instances used up") }),
                    "working" to EmptyFolder(onListing = { swept.complete(Unit) }),
                )
            val watching =
                DefaultAppEnvironment(
                    settings,
                    watchedFolder = { folders[it] },
                    log = { _, _, error -> if (error is IOException) failed.complete(Unit) },
                    factory = factory,
                )
            try {
                watching.openLibrary(good)
                settings.updateSettings { it.copy(watchedFolder = "broken") }
                failed.await()

                settings.updateSettings { it.copy(watchedFolder = "working") }

                swept.await()
            } finally {
                watching.closeLibrary()
                watching.appScope.cancel()
            }
        }

    /** A watched folder with no PDFs in it, which says when it is listed. */
    private class EmptyFolder(
        private val changes: Flow<Unit> = emptyFlow(),
        private val onListing: () -> Unit = {},
    ) : WatchedFolder {
        override suspend fun listPdfs(): List<WatchedFolderFile> {
            onListing()
            return emptyList()
        }

        override suspend fun delete(
            file: WatchedFolderFile,
            docId: String,
        ) = false

        override fun changes(): Flow<Unit> = changes
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
