package dev.npn.app

import dev.npn.app.di.LibraryUnavailableException
import dev.npn.app.di.OpenFailureReason
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.nav.QueueStatus
import dev.npn.core.ai.AiProvider
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.RebuildOutcome
import dev.npn.core.app.WorkStage
import dev.npn.core.app.WorkState
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.index.WriteBlock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_writes_journal_incomplete
import no_papers_needed.composeapp.generated.resources.library_rebuild_failed
import no_papers_needed.composeapp.generated.resources.library_rebuild_not_completed
import no_papers_needed.composeapp.generated.resources.library_refresh_failed
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test

class AppViewModelTest : MainDispatcherTest() {
    private val io = StandardTestDispatcher(dispatcher.scheduler, name = "io")

    /** Folders created, as (parent, name, the dispatcher it ran on). */
    private val created = mutableListOf<Triple<String, String, ContinuationInterceptor?>>()

    /** What creating a folder returns; null is a folder that could not be created. */
    private var createdLocation: (String, String) -> String? = { parent, name -> "$parent/$name" }

    private fun TestScope.model(env: FakeAppEnvironment): AppViewModel {
        val vm =
            AppViewModel(env, io) { parent, name ->
                created += Triple(parent, name, currentCoroutineContext()[ContinuationInterceptor])
                createdLocation(parent, name)
            }
        collecting(vm.state)
        runCurrent()
        return vm
    }

    private fun TestScope.env(savedLocation: String? = null) = FakeAppEnvironment(backgroundScope, savedLocation)

    /** The shell's queue badge follows the open library's queues, and clears with no library open. */
    @Test
    fun theQueueBadgeFollowsTheOpenLibrary() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib")
            val vm = model(env)
            collecting(vm.queueStatus)
            runCurrent()
            vm.queueStatus.value shouldBe QueueStatus.None

            env.sessions
                .getValue("/lib")
                .work.queueEntries.value =
                listOf(QueuedWork("d1", "d1.pdf", WorkStage.OCR, WorkState.WAITING, attempts = 0, updatedAt = 0L))
            runCurrent()
            vm.queueStatus.value shouldBe QueueStatus(pending = 1)

            env.closeLibrary()
            runCurrent()
            vm.queueStatus.value shouldBe QueueStatus.None
        }

    /**
     * The read-only banner shows why the open library refuses writes for as long as its session
     * says so, goes once a pass lifts the block, and goes with the library.
     */
    @Test
    fun theReadOnlyBannerFollowsTheOpenLibrarysWriteBlock() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib")
            val vm = model(env)
            collecting(vm.writeBlock)
            runCurrent()
            vm.writeBlock.value shouldBe null

            val session = env.sessions.getValue("/lib")
            session.writeBlock.value = WriteBlock.JOURNAL_DAMAGED
            runCurrent()
            vm.writeBlock.value shouldBe WriteBlock.JOURNAL_DAMAGED

            session.writeBlock.value = null
            runCurrent()
            vm.writeBlock.value shouldBe null

            session.writeBlock.value = WriteBlock.NEWER_FORMAT
            runCurrent()
            vm.writeBlock.value shouldBe WriteBlock.NEWER_FORMAT

            env.closeLibrary()
            runCurrent()
            vm.writeBlock.value shouldBe null
        }

    /**
     * The block is the session's, not the window's: a recreated shell shows it again from the
     * session, with nothing of it in its saved state.
     */
    @Test
    fun aRecreatedShellShowsTheBlockAgain() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib")
            val screen =
                recreatable {
                    AppViewModel(env, io) { parent, name -> createdLocation(parent, name) }
                }
            collecting(screen.vm.state)
            runCurrent()
            env.sessions
                .getValue("/lib")
                .writeBlock.value = WriteBlock.RECONCILE_FAILED

            val restored = screen.recreate(this, { it.writeBlock })
            restored.writeBlock.value shouldBe WriteBlock.RECONCILE_FAILED
        }

    /** Retry reads the open library's journal again, a rescan, and says nothing when it succeeds. */
    @Test
    fun retryRescansTheOpenLibrary() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib")
            val vm = model(env)
            val messages = collecting(vm.messages.map { it.text })

            vm.retryWrites()
            runCurrent()

            env.sessions
                .getValue("/lib")
                .ingest.rescans shouldBe 1
            messages shouldBe emptyList()
        }

    /**
     * Rebuild index builds the open library's index again from its folder, saying nothing when that
     * is done; a rebuild that did not finish says so, and one that threw gives its reason.
     */
    @Test
    fun rebuildIndexRebuildsTheOpenLibrarysIndexAndSaysWhyWhenItCannot() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib")
            val vm = model(env)
            val messages = collecting(vm.messages.map { it.text })
            val maintenance = env.sessions.getValue("/lib").maintenance

            vm.rebuildIndex()
            runCurrent()
            maintenance.rebuildOutcome = RebuildOutcome.Failed(WriteBlock.JOURNAL_DAMAGED)
            vm.rebuildIndex()
            runCurrent()
            maintenance.failRebuild = { error("disk full") }
            vm.rebuildIndex()
            runCurrent()

            maintenance.rebuilds shouldBe 2
            messages shouldBe
                listOf(
                    uiText(Res.string.library_rebuild_not_completed),
                    failure(Res.string.library_rebuild_failed, "disk full"),
                )
        }

    /**
     * A retry whose rescan still finds the journal incomplete says so in the user's terms, as a
     * refused write does; any other failure gives its reason.
     */
    @Test
    fun aRetryThatFailsSaysWhy() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib")
            val vm = model(env)
            val messages = collecting(vm.messages.map { it.text })
            val ingest = env.sessions.getValue("/lib").ingest

            ingest.failRescan = { throw MetadataWriteBlocked(WriteBlock.JOURNAL_DAMAGED, "Object is not valid UTF-8") }
            vm.retryWrites()
            runCurrent()
            ingest.failRescan = { error("folder unavailable") }
            vm.retryWrites()
            runCurrent()

            messages shouldBe
                listOf(
                    uiText(Res.string.library_refresh_failed, uiText(Res.string.common_writes_journal_incomplete)),
                    failure(Res.string.library_refresh_failed, "folder unavailable"),
                )
        }

    /**
     * A restored process opens the library the window had open, from the saved location, as any
     * start does: nothing about it lives in the window's saved state.
     */
    @Test
    fun aRestoredProcessOpensTheSameLibrary() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib")
            val screen =
                recreatable {
                    AppViewModel(env, io) { parent, name -> createdLocation(parent, name) }
                }

            val restored = screen.recreate(this, { it.state })
            restored.state.value shouldBe AppState.Open(env.sessions.getValue("/lib"))
        }

    @Test
    fun theSavedLibraryOpensAtStart() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib")
            val vm = model(env)

            vm.state.value shouldBe AppState.Open(env.sessions.getValue("/lib"))
        }

    @Test
    fun startIsRestoringUntilTheSavedLibraryHasOpened() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib").apply { gate = CompletableDeferred() }
            val vm = model(env)

            vm.state.value shouldBe AppState.Restoring

            env.gate?.complete(Unit)
            runCurrent()

            vm.state.value.shouldBeInstanceOf<AppState.Open>()
        }

    @Test
    fun withNothingSavedTheAppStartsAtOnboarding() =
        runTest(dispatcher) {
            val vm = model(env())

            vm.state.value shouldBe AppState.NoLibrary()
        }

    @Test
    fun aSavedLibraryThatCannotOpenSaysWhyInTheUsersTerms() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/usb/lib")
            env.failures["/usb/lib"] =
                LibraryUnavailableException(OpenFailureReason.FOLDER_MISSING, "Library folder not found: /usb/lib")
            val vm = model(env)

            vm.state.value shouldBe
                AppState.RestoreFailed(
                    OpenFailure(OpenFailureReason.FOLDER_MISSING, "Library folder not found: /usb/lib"),
                )
        }

    @Test
    fun anUnexpectedFailureReadsAsUnreadableWithTheCauseOnlyAsADetail() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib")
            env.failures["/lib"] = IllegalStateException("database disk image is malformed")
            val vm = model(env)

            vm.state.value shouldBe
                AppState.RestoreFailed(
                    OpenFailure(OpenFailureReason.UNREADABLE, "database disk image is malformed"),
                )
        }

    /**
     * A library holding what only a newer version of the app reads asks for an update rather than
     * calling the library unreadable; a damaged journal still reads as unreadable.
     */
    @Test
    fun aLibraryANewerAppChangedAsksForAnUpdate() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/lib")
            env.failures["/lib"] =
                MetadataWriteBlocked(WriteBlock.NEWER_FORMAT, "Object line 1 uses unknown operation x")
            val vm = model(env)

            vm.state.value shouldBe
                AppState.RestoreFailed(
                    OpenFailure(OpenFailureReason.NEEDS_NEWER_APP, "Object line 1 uses unknown operation x"),
                )

            env.failures["/lib"] = MetadataWriteBlocked(WriteBlock.JOURNAL_DAMAGED, "Object is not valid UTF-8")
            vm.retry()
            runCurrent()

            vm.state.value shouldBe
                AppState.RestoreFailed(OpenFailure(OpenFailureReason.UNREADABLE, "Object is not valid UTF-8"))
        }

    @Test
    fun retryOpensTheSavedLibraryOnceItCan() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/usb/lib")
            env.failures["/usb/lib"] = IllegalStateException("not mounted")
            val vm = model(env)
            vm.state.value.shouldBeInstanceOf<AppState.RestoreFailed>()

            env.failures.clear()
            vm.retry()
            runCurrent()

            vm.state.value shouldBe AppState.Open(env.sessions.getValue("/usb/lib"))
        }

    @Test
    fun anotherFolderPickedAfterAFailedRestoreOpensAndIsSaved() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/usb/lib")
            env.failures["/usb/lib"] = IllegalStateException("not mounted")
            val vm = model(env)

            vm.open("/home/lib")
            runCurrent()

            vm.state.value shouldBe AppState.Open(env.sessions.getValue("/home/lib"))
            env.settings.settingsFlow.value
                .libraryLocation shouldBe "/home/lib"
        }

    @Test
    fun aPickedFolderThatAlsoFailsKeepsTheWayBackToTheSavedLibrary() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/usb/lib")
            env.failures["/usb/lib"] = IllegalStateException("not mounted")
            env.failures["/other"] = IllegalStateException("broken index")
            val vm = model(env)

            vm.open("/other")
            runCurrent()

            vm.state.value shouldBe AppState.RestoreFailed(OpenFailure(OpenFailureReason.UNREADABLE, "broken index"))
            env.settings.settingsFlow.value
                .libraryLocation shouldBe "/usb/lib"
        }

    @Test
    fun anOpenInFlightShowsOpening() =
        runTest(dispatcher) {
            val env = env()
            val vm = model(env)
            env.gate = CompletableDeferred()

            vm.open("/lib")
            runCurrent()
            vm.state.value shouldBe AppState.Opening

            env.gate?.complete(Unit)
            runCurrent()
            vm.state.value shouldBe AppState.Open(env.sessions.getValue("/lib"))
        }

    @Test
    fun aFailedFirstOpenStaysOnOnboardingAndSaysWhy() =
        runTest(dispatcher) {
            val env = env()
            env.failures["/cloud"] =
                LibraryUnavailableException(OpenFailureReason.NO_STORAGE_ACCESS, "Storage access was revoked")
            val vm = model(env)

            vm.open("/cloud")
            runCurrent()

            vm.state.value shouldBe
                AppState.NoLibrary(OpenFailure(OpenFailureReason.NO_STORAGE_ACCESS, "Storage access was revoked"))
        }

    @Test
    fun aFailedSwitchKeepsTheOpenLibraryAndSaysWhyUntilDismissed() =
        runTest(dispatcher) {
            val env = env(savedLocation = "/a")
            env.failures["/b"] = IllegalStateException("broken index")
            val vm = model(env)
            val first = env.sessions.getValue("/a")

            vm.open("/b")
            runCurrent()

            vm.state.value shouldBe AppState.Open(first, OpenFailure(OpenFailureReason.UNREADABLE, "broken index"))

            vm.dismissFailure()
            runCurrent()

            vm.state.value shouldBe AppState.Open(first)
        }

    @Test
    fun aLibraryOpenedOutsideTheShellShowsUp() =
        runTest(dispatcher) {
            val env = env()
            val vm = model(env)

            // A share intent restoring the library on a cold start, say.
            env.session.value = FakeLibrarySession()
            runCurrent()

            vm.state.value.shouldBeInstanceOf<AppState.Open>()
        }

    @Test
    fun aNewLibraryFolderIsCreatedOffTheMainThreadThenOpened() =
        runTest(dispatcher) {
            val env = env()
            val vm = model(env)

            vm.createLibrary("/home/me", "Papers")
            runCurrent()

            created shouldContainExactly listOf(Triple("/home/me", "Papers", io))
            env.opened shouldContainExactly listOf("/home/me/Papers")
            vm.state.value shouldBe AppState.Open(env.sessions.getValue("/home/me/Papers"))
        }

    @Test
    fun theRecommendedFolderIsCreatedInItsParent() =
        runTest(dispatcher) {
            val vm = model(env())

            vm.useRecommended("/storage/emulated/0/Documents/NoPapersNeeded")
            runCurrent()

            created.map { it.first to it.second } shouldContainExactly
                listOf("/storage/emulated/0/Documents" to "NoPapersNeeded")
        }

    @Test
    fun aFolderThatCannotBeCreatedSaysSoAndOpensNothing() =
        runTest(dispatcher) {
            createdLocation = { _, _ -> null }
            val env = env()
            val vm = model(env)

            vm.createLibrary("/read-only", "Papers")
            runCurrent()

            vm.state.value shouldBe AppState.NoLibrary(OpenFailure(OpenFailureReason.FOLDER_NOT_CREATED))
            env.opened shouldBe emptyList()
        }

    @Test
    fun theStorageAccessFrameworkChoiceIsSaved() =
        runTest(dispatcher) {
            val env = env()
            val vm = model(env)
            collecting(vm.useSafStorage)

            vm.setUseSafStorage(true)
            runCurrent()

            vm.useSafStorage.value shouldBe true
            env.settings.settingsFlow.value
                .useSafStorage shouldBe true
        }

    /** A window recreated with a language override composes in it from its first frame. */
    @Test
    fun theAppLanguageIsTheSettingsFromTheStart() =
        runTest(dispatcher) {
            val env = env().apply { settings.updateSettings { it.copy(appLanguage = "nl") } }
            val vm = model(env)
            vm.appLanguage.value shouldBe "nl"

            collecting(vm.appLanguage)
            env.settings.edit { it.copy(appLanguage = null) }
            runCurrent()
            vm.appLanguage.value shouldBe null
        }

    @Test
    fun aiConfiguredFollowsSettingsFromTheStart() =
        runTest(dispatcher) {
            val env = env()
            val vm = model(env)
            vm.aiConfigured.value shouldBe false

            env.settings.edit {
                it.copy(aiEnabled = true, aiProvider = AiProvider.OLLAMA, aiModel = "qwen3:8b")
            }
            runCurrent()

            vm.aiConfigured.value shouldBe true
        }
}
