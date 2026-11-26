package dev.npn.app

import dev.npn.app.di.LibraryUnavailableException
import dev.npn.app.di.OpenFailureReason
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.diagnostics.ProblemReportState
import dev.npn.app.ui.nav.QueueStatus
import dev.npn.app.ui.onboarding.FirstDocumentText
import dev.npn.app.ui.settings.CloudOAuthApps
import dev.npn.app.ui.settings.CloudSetupState
import dev.npn.core.ai.AiProvider
import dev.npn.core.app.QueuedWork
import dev.npn.core.app.RebuildOutcome
import dev.npn.core.app.WorkStage
import dev.npn.core.app.WorkState
import dev.npn.core.index.MetadataWriteBlocked
import dev.npn.core.index.WriteBlock
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.sync.RcloneProvider
import dev.npn.core.sync.SetupStep
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_onboarding_cloud_connect_failed
import no_papers_needed.composeapp.generated.resources.common_writes_journal_incomplete
import no_papers_needed.composeapp.generated.resources.email_imported_documents
import no_papers_needed.composeapp.generated.resources.library_rebuild_failed
import no_papers_needed.composeapp.generated.resources.library_rebuild_not_completed
import no_papers_needed.composeapp.generated.resources.library_refresh_failed
import no_papers_needed.composeapp.generated.resources.library_show
import no_papers_needed.composeapp.generated.resources.onboarding_landed_reading
import no_papers_needed.composeapp.generated.resources.onboarding_landed_search
import kotlin.coroutines.ContinuationInterceptor
import kotlin.test.Test

class AppViewModelTest : MainDispatcherTest() {
    private val io = StandardTestDispatcher(dispatcher.scheduler, name = "io")

    /** Folders created, as (parent, name, the dispatcher it ran on). */
    private val created = mutableListOf<Triple<String, String, ContinuationInterceptor?>>()

    /** What creating a folder returns; null is a folder that could not be created. */
    private var createdLocation: (String, String) -> String? = { parent, name -> "$parent/$name" }

    /** The app log a problem report reads. */
    private var logTail = ""

    private fun TestScope.model(env: FakeAppEnvironment): AppViewModel {
        val vm =
            AppViewModel(
                env,
                io,
                createFolder = { parent, name ->
                    created += Triple(parent, name, currentCoroutineContext()[ContinuationInterceptor])
                    createdLocation(parent, name)
                },
                cloudLibraries = { CLOUD_LIBRARIES },
                newRemoteName = { type -> "$type-1" },
                oauthApps = CloudOAuthApps.NONE,
                compute = dispatcher,
                readLog = { logTail },
            )
        collecting(vm.state)
        runCurrent()
        return vm
    }

    private fun TestScope.env(
        savedLocation: String? = null,
        cloudSetup: FakeCloudSetup? = null,
    ) = FakeAppEnvironment(backgroundScope, savedLocation, cloudSetup)

    /** A cloud setup whose provider needs no form and whose sign-in finishes at once. */
    private fun signedInSetup() =
        FakeCloudSetup().apply {
            providersResult =
                listOf(RcloneProvider(type = "drive", name = "Google Drive", description = "", options = emptyList()))
            beginResult = { SetupStep.Done(it.name) }
        }

    /** Run the onboarding's cloud wizard up to the folder picker, signed in to `drive-1`. */
    private fun TestScope.signIn(vm: AppViewModel) {
        vm.cloudSetup.start()
        runCurrent()
        val providers = vm.cloudSetupState.value.shouldBeInstanceOf<CloudSetupState.Providers>()
        vm.cloudSetup.chooseProvider(providers.providers.single())
        vm.cloudSetup.submit()
        runCurrent()
    }

    /**
     * Where no library opens, Settings is out of reach, so the failure offers "Report a problem"
     * itself: the bundle carries the device's side and the log that says why, and no library.
     */
    @Test
    fun aProblemCanBeReportedWithNoLibraryOpen() =
        runTest(dispatcher) {
            logTail = "2026-11-22T10:00:00Z WARN Opening the library failed"
            val vm = model(env())
            collecting(vm.problemReport)
            vm.state.value.shouldBeInstanceOf<AppState.NoLibrary>()

            vm.reportProblem()
            advanceUntilIdle()

            val ready = vm.problemReport.value.shouldBeInstanceOf<ProblemReportState.Ready>()
            ready.text shouldContain "Version: ${BuildInfo.VERSION}"
            ready.text shouldContain "2026-11-22T10:00:00Z WARN Opening the library failed"
            ready.text shouldNotContain "## Library"
            ready.text shouldNotContain "## Cloud sync"

            vm.problemReportHandled()
            vm.problemReport.value shouldBe ProblemReportState.Idle
        }

    /**
     * Choosing a cloud folder in the onboarding is all the user does: the app makes the working copy
     * in its own folder, opens it, connects it to that cloud folder with automatic sync on, and
     * starts the first pass, which brings in what the folder already holds. The onboarding's own
     * engine is closed once the library's takes over.
     */
    @Test
    fun choosingACloudFolderOpensAWorkingCopyConnectedToIt() =
        runTest(dispatcher) {
            val setup = signedInSetup()
            val env = env(cloudSetup = setup)
            val vm = model(env)
            vm.cloudAvailable shouldBe true

            signIn(vm)
            vm.cloudSetup.createFolder("NoPapersNeeded")
            runCurrent()
            vm.cloudSetup.useFolder()
            runCurrent()

            created.map { (parent, name) -> parent to name } shouldContainExactly listOf(CLOUD_LIBRARIES to "drive-1")
            env.opened shouldContainExactly listOf("$CLOUD_LIBRARIES/drive-1")
            vm.state.value
                .shouldBeInstanceOf<AppState.Open>()
                .failure shouldBe null
            val sync = env.sessions.getValue("$CLOUD_LIBRARIES/drive-1").sync
            sync.configFlow.value shouldBe CloudSyncConfig(enabled = true, remote = "drive-1:NoPapersNeeded")
            sync.manualSyncs shouldBe 1
            vm.cloudSetupState.value shouldBe null
            env.cloudEnginesClosed shouldBe env.cloudEnginesStarted
            setup.deletedRemotes shouldBe emptyList()
        }

    /** A cloud library that does not open says why and forgets the sign-in it no longer needs. */
    @Test
    fun aCloudLibraryThatDoesNotOpenForgetsItsRemote() =
        runTest(dispatcher) {
            val setup = signedInSetup()
            val env = env(cloudSetup = setup)
            env.failures["$CLOUD_LIBRARIES/drive-1"] =
                LibraryUnavailableException(OpenFailureReason.FOLDER_MISSING, "gone")
            val vm = model(env)

            signIn(vm)
            vm.cloudSetup.createFolder("NoPapersNeeded")
            runCurrent()
            vm.cloudSetup.useFolder()
            runCurrent()

            vm.state.value shouldBe AppState.NoLibrary(OpenFailure(OpenFailureReason.FOLDER_MISSING, "gone"))
            setup.deletedRemotes shouldContainExactly listOf("drive-1")
        }

    /** A working copy that cannot be made says so, and forgets the sign-in too. */
    @Test
    fun aCloudLibraryWhoseWorkingCopyCannotBeMadeForgetsItsRemote() =
        runTest(dispatcher) {
            val setup = signedInSetup()
            val env = env(cloudSetup = setup)
            createdLocation = { _, _ -> null }
            val vm = model(env)

            signIn(vm)
            vm.cloudSetup.createFolder("NoPapersNeeded")
            runCurrent()
            vm.cloudSetup.useFolder()
            runCurrent()

            vm.state.value shouldBe AppState.NoLibrary(OpenFailure(OpenFailureReason.FOLDER_NOT_CREATED))
            env.opened shouldBe emptyList()
            setup.deletedRemotes shouldContainExactly listOf("drive-1")
        }

    /** Backing out after signing in, before a folder is chosen, leaves no sign-in behind. */
    @Test
    fun backingOutOfTheCloudWizardAfterSignInForgetsTheRemote() =
        runTest(dispatcher) {
            val setup = signedInSetup()
            val vm = model(env(cloudSetup = setup))

            signIn(vm)
            vm.cloudSetupState.value.shouldBeInstanceOf<CloudSetupState.Folders>()
            vm.cloudSetup.cancel()
            runCurrent()

            vm.cloudSetupState.value shouldBe null
            setup.deletedRemotes shouldContainExactly listOf("drive-1")
        }

    /** A library connected to the cloud whose connection cannot be saved still opens, and says so. */
    @Test
    fun aCloudLibraryWhoseConnectionCannotBeSavedSaysSo() =
        runTest(dispatcher) {
            val env = env(cloudSetup = signedInSetup())
            val session = FakeLibrarySession()
            session.sync.configureFailure = IllegalStateException("disk full")
            env.sessions["$CLOUD_LIBRARIES/drive-1"] = session
            val vm = model(env)
            val messages = collecting(vm.messages.map { it.text })

            signIn(vm)
            vm.cloudSetup.createFolder("NoPapersNeeded")
            runCurrent()
            vm.cloudSetup.useFolder()
            runCurrent()

            vm.state.value.shouldBeInstanceOf<AppState.Open>()
            messages shouldContainExactly
                listOf(uiText(Res.string.common_onboarding_cloud_connect_failed, UiText.Raw("disk full")))
            session.sync.manualSyncs shouldBe 0
        }

    /** Without a setup engine on this platform, onboarding offers folders only. */
    @Test
    fun withoutASetupEngineNoCloudLibraryIsOffered() =
        runTest(dispatcher) {
            val vm = model(env())
            vm.cloudAvailable shouldBe false
        }

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
            maintenance.rebuildOutcome = RebuildOutcome.Failed
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

    /** Desktop proposes a folder in the documents folder, which on Windows has backslashes. */
    @Test
    fun aWindowsRecommendedFolderIsCreatedInItsParent() =
        runTest(dispatcher) {
            val vm = model(env())

            vm.useRecommended("C:\\Users\\me\\Documents\\NoPapersNeeded")
            runCurrent()

            created.map { it.first to it.second } shouldContainExactly
                listOf("C:\\Users\\me\\Documents" to "NoPapersNeeded")
        }

    /**
     * Landing on the first document says how to find it again: right away once its text is read,
     * once it is otherwise.
     */
    @Test
    fun landingOnTheFirstDocumentSaysHowToFindItAgain() =
        runTest(dispatcher) {
            val vm = model(env(savedLocation = "/lib"))
            val messages = collecting(vm.messages.map { it.text })

            vm.firstDocumentLanded(FirstDocumentText.READ)
            vm.firstDocumentLanded(FirstDocumentText.READING)
            // A document no text came of has nothing to search for: nothing is said.
            vm.firstDocumentLanded(FirstDocumentText.UNREADABLE)
            runCurrent()

            messages shouldContainExactly
                listOf(uiText(Res.string.onboarding_landed_search), uiText(Res.string.onboarding_landed_reading))
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

    /** The reduced-motion preference reaches the shell from its first frame and follows later edits. */
    @Test
    fun reducedMotionIsTheSettingsFromTheStartAndFollowsThem() =
        runTest(dispatcher) {
            val env = env().apply { settings.updateSettings { it.copy(reducedMotion = true) } }
            val vm = model(env)
            vm.reducedMotion.value shouldBe true

            collecting(vm.reducedMotion)
            env.settings.edit { it.copy(reducedMotion = false) }
            runCurrent()
            vm.reducedMotion.value shouldBe false
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

    /**
     * A background email check that imported documents says so from any screen, and its Show opens
     * the library on exactly those.
     */
    @Test
    fun documentsAnEmailCheckImportedAreAnnouncedWithAWayToThem() =
        runTest(dispatcher) {
            val env = env()
            val vm = model(env)
            vm.open("/lib")
            runCurrent()
            val messages = collecting(vm.messages)
            val shown = mutableListOf<List<String>>()
            backgroundScope.launch { vm.announceMailImports { shown += it } }
            runCurrent()

            val mail = env.sessions.getValue("/lib").mail
            mail.importedDocuments.emit(listOf("doc-1", "doc-2"))
            runCurrent()

            val message = messages.single()
            message.text shouldBe uiText(Res.plurals.email_imported_documents, 2, 2)
            val show = message.action!!
            show.label shouldBe uiText(Res.string.library_show)
            show.onPerform()
            shown shouldContainExactly listOf(listOf("doc-1", "doc-2"))
        }
}

/** Where the tests' cloud libraries keep their working copies. */
private const val CLOUD_LIBRARIES = "/app/cloud-libraries"
