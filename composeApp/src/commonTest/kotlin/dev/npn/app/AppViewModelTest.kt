package dev.npn.app

import dev.npn.app.di.LibraryUnavailableException
import dev.npn.app.di.OpenFailureReason
import dev.npn.app.ui.nav.QueueStatus
import dev.npn.core.ai.AiProvider
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.WorkStage
import dev.npn.core.app.WorkState
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.index.WriteBlock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
                ?.libraryLocation shouldBe "/home/lib"
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
                ?.libraryLocation shouldBe "/usb/lib"
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
                ?.useSafStorage shouldBe true
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
