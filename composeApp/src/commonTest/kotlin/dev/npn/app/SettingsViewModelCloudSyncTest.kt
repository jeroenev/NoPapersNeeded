package dev.npn.app

import dev.npn.app.ui.settings.CloudSetupState
import dev.npn.app.ui.settings.CloudSyncState
import dev.npn.app.ui.settings.FolderProblem
import dev.npn.app.ui.settings.SettingsViewModel
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.sync.FolderContents
import dev.npn.core.sync.OAuthStatus
import dev.npn.core.sync.RcloneOption
import dev.npn.core.sync.RcloneProvider
import dev.npn.core.sync.SetupFailure
import dev.npn.core.sync.SetupStep
import dev.npn.core.sync.SyncFailure
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncReport
import dev.npn.core.sync.SyncStatus
import dev.npn.core.sync.TransportAvailability
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import kotlin.test.Test

/**
 * The cloud sync corner of [SettingsViewModel]: probing the transfer engine, the library's own
 * remote and schedule, the setup wizard and asking for a pass, all against [FakeSyncFacade]. Split
 * from [SettingsViewModelTest] to keep either class within bounds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelCloudSyncTest : MainDispatcherTest() {
    /** A viewmodel over [session] whose pane state is collected, as the screen collects it. */
    private fun TestScope.vm(session: FakeLibrarySession): SettingsViewModel =
        settingsVm(session = session).also { collecting(it.paneState) }

    private val SettingsViewModel.cloudState: CloudSyncState get() = paneState.value.cloud

    @Test
    fun onlyTheLatestProbeMayAnswer() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            val gate = CompletableDeferred<Unit>()

            // A probe for a half-typed path that never comes back...
            session.sync.availabilityGate = gate
            model.probeTransport()
            runCurrent()
            session.sync.probes shouldBe 1
            model.cloudState.transport.shouldBeNull()

            // ...and the one for the finished path, which answers straight away.
            session.sync.availabilityGate = null
            session.sync.availability = TransportAvailability.Available("v1.70")
            model.probeTransport()
            advanceUntilIdle()
            model.cloudState.transport shouldBe TransportAvailability.Available("v1.70")

            // The stale probe finishing late must not write over that answer.
            session.sync.availability = TransportAvailability.NotInstalled("/half/typed")
            gate.complete(Unit)
            advanceUntilIdle()
            model.cloudState.transport shouldBe TransportAvailability.Available("v1.70")
            session.sync.probes shouldBe 2
        }

    @Test
    fun aThrowingFacadeEndsAsAFailedOutcomeInsteadOfACrash() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            session.sync.syncNowFailure = IllegalStateException("engine exploded")

            model.syncNow()
            advanceUntilIdle()

            model.cloudState.outcome shouldBe SyncOutcome.Failed(SyncFailure.UNKNOWN, "engine exploded")
            session.sync.manualSyncs shouldBe 1
        }

    @Test
    fun aLaterPassStartingClearsTheManualOutcome() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            val report = SyncReport(startedAt = 1L, durationMillis = 2L, uploadedDocuments = 1)
            session.sync.outcome = SyncOutcome.Completed(report)

            model.syncNow()
            advanceUntilIdle()
            model.cloudState.outcome shouldBe SyncOutcome.Completed(report)

            // An automatic pass starts: its result is what the status shows from here on.
            session.sync.statusFlow.value = SyncStatus(running = true)
            advanceUntilIdle()
            model.cloudState.outcome.shouldBeNull()
            model.cloudState.status.running shouldBe true
        }

    @Test
    fun theWizardLoadsProvidersAndBeginsTheFormWithTheDefaults() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            val chosen = provider()
            setup.providersResult = listOf(chosen)
            setup.beginResult = { question("client_id", "state-1") }
            val model = vm(session)

            model.cloud.available shouldBe true
            model.cloud.start()
            advanceUntilIdle()
            model.cloudState.setup shouldBe CloudSetupState.Providers(listOf(chosen))

            model.cloud.chooseProvider(chosen)
            model.cloud.setValue("client_secret", "sekret")
            model.cloud.submit()
            advanceUntilIdle()

            // Every setup names its remote afresh, so it never overwrites another one's sign-in.
            setup.begins.single() shouldBe
                FakeCloudSetup.BeginCall(
                    name = "drive-1",
                    type = "drive",
                    params = mapOf("client_id" to "default-id", "client_secret" to "sekret"),
                )
            // The question rclone asked is what the form shows next.
            (model.cloudState.setup as CloudSetupState.Form).question?.option?.name shouldBe "client_id"
        }

    @Test
    fun aBrowserSignInQuestionIsAnsweredForTheUserAndItsUrlSurfaces() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            val chosen = provider(options = emptyList())
            val gate = CompletableDeferred<Unit>()
            setup.answerGate = gate
            setup.beginResult = { question("config_is_local", "oauth-state") }
            setup.answerResult = { SetupStep.Done("drive") }
            setup.oauthStatusResult = OAuthStatus(running = true, authUrl = "http://127.0.0.1:53682/auth")
            val model = vm(session)

            model.cloud.start()
            advanceUntilIdle()
            model.cloud.chooseProvider(chosen)
            model.cloud.submit()
            // The blocking answer is held on the gate; the poll runs while it waits.
            runCurrent()

            setup.answers.single() shouldBe FakeCloudSetup.AnswerCall("drive", "oauth-state", "true")
            (model.cloudState.setup as CloudSetupState.SigningIn).authUrl shouldBe "http://127.0.0.1:53682/auth"
            setup.oauthStatusCalls shouldBe 1

            gate.complete(Unit)
            advanceUntilIdle()
            model.cloudState.setup shouldBe CloudSetupState.Folders(remote = "drive:", path = "")
        }

    @Test
    fun aBrowserProviderSkipsTheFormAndAnswersRclonesOwnQuestions() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            val chosen = provider(options = emptyList(), browserSignIn = true)
            setup.beginResult = { question("config_shared_client_id", "client_id_warning") }
            setup.answerResult = { call ->
                if (call.state == "client_id_warning") {
                    question("config_is_local", "oauth-state")
                } else {
                    SetupStep.Done("drive")
                }
            }
            val model = vm(session)

            model.cloud.start()
            advanceUntilIdle()
            model.cloud.chooseProvider(chosen)
            advanceUntilIdle()

            // No form is ever shown: the wizard starts the conversation with no parameters...
            setup.begins.single() shouldBe FakeCloudSetup.BeginCall("drive-1", "drive", emptyMap())
            // ...answers Drive's shared-client warning itself, then the browser prompt too.
            setup.answers shouldBe
                listOf(
                    FakeCloudSetup.AnswerCall("drive", "client_id_warning", "true"),
                    FakeCloudSetup.AnswerCall("drive", "oauth-state", "true"),
                )
            model.cloudState.setup shouldBe CloudSetupState.Folders(remote = "drive:", path = "")
        }

    @Test
    fun aGenericQuestionMapsToAnAnswer() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            val chosen = provider()
            setup.beginResult = { question("client_secret", "state-1") }
            setup.answerResult = { SetupStep.Done("drive") }
            val model = vm(session)

            model.cloud.start()
            advanceUntilIdle()
            model.cloud.chooseProvider(chosen)
            model.cloud.submit()
            advanceUntilIdle()
            model.cloud.setValue("client_secret", "abc")
            model.cloud.submit()
            advanceUntilIdle()

            setup.answers.single() shouldBe FakeCloudSetup.AnswerCall("drive", "state-1", "abc")
            model.cloudState.setup shouldBe CloudSetupState.Folders(remote = "drive:", path = "")
        }

    @Test
    fun choosingAFolderPersistsItAsTheLibrarysRemote() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            val chosen = provider(options = emptyList())
            setup.beginResult = { SetupStep.Done("drive") }
            setup.foldersResult = { listOf("NoPapersNeeded", "Archive") }
            val model = vm(session)

            model.cloud.start()
            advanceUntilIdle()
            model.cloud.chooseProvider(chosen)
            model.cloud.submit()
            advanceUntilIdle()
            model.cloudState.setup shouldBe
                CloudSetupState.Folders(
                    remote = "drive:",
                    path = "",
                    folders = listOf("NoPapersNeeded", "Archive"),
                )

            model.cloud.openFolder("NoPapersNeeded")
            advanceUntilIdle()
            setup.folderLists.last() shouldBe FakeCloudSetup.FolderCall("drive:", "NoPapersNeeded")

            model.cloud.useFolder()
            advanceUntilIdle()

            // The library's own setting on this device, not the device's: another library keeps its own.
            session.sync.configFlow.value.remote shouldBe "drive:NoPapersNeeded"
            model.cloudState.config.remote shouldBe "drive:NoPapersNeeded"
            model.cloudState.setup.shouldBeNull()
        }

    @Test
    fun theTopLevelAndAFolderOfOtherFilesAreNeverTheLibrarysFolder() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            setup.beginResult = { SetupStep.Done("drive") }
            setup.foldersResult = { listOf("Documents") }
            setup.contentsResult = { FolderContents.OTHER_FILES }
            val model = vm(session)
            model.cloud.start()
            advanceUntilIdle()
            model.cloud.chooseProvider(provider(options = emptyList()))
            model.cloud.submit()
            advanceUntilIdle()

            // The drive's top level: everything the user stores, so not offered at all.
            (model.cloudState.setup as CloudSetupState.Folders).usable shouldBe false
            model.cloud.useFolder()
            advanceUntilIdle()
            session.sync.configFlow.value.remote
                .shouldBeNull()

            // A folder of the user's other files: refused, and said why.
            model.cloud.openFolder("Documents")
            advanceUntilIdle()
            model.cloud.useFolder()
            advanceUntilIdle()
            (model.cloudState.setup as CloudSetupState.Folders).problem shouldBe FolderProblem.OTHER_FILES
            session.sync.configFlow.value.remote
                .shouldBeNull()

            // An empty folder, or a library's, is taken.
            setup.contentsResult = { FolderContents.LIBRARY }
            model.cloud.useFolder()
            advanceUntilIdle()
            session.sync.configFlow.value.remote shouldBe "drive:Documents"
        }

    @Test
    fun aFolderThatCannotBeCreatedSaysWhy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            setup.beginResult = { SetupStep.Done("drive") }
            setup.makeFolderFailure = IllegalStateException("quota exceeded")
            val model = vm(session)
            model.cloud.start()
            advanceUntilIdle()
            model.cloud.chooseProvider(provider(options = emptyList()))
            model.cloud.submit()
            advanceUntilIdle()

            model.cloud.createFolder("NoPapersNeeded")
            advanceUntilIdle()

            val folders = model.cloudState.setup as CloudSetupState.Folders
            folders.problem shouldBe FolderProblem.CREATE_FAILED
            folders.detail shouldBe "quota exceeded"
            folders.path shouldBe ""
        }

    @Test
    fun changingTheFolderBrowsesTheConfiguredRemote() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            session.sync.configFlow.value = CloudSyncConfig(remote = "drive-1:Lib/Sub")
            val model = vm(session)

            model.cloud.changeFolder()
            advanceUntilIdle()
            setup.folderLists.last() shouldBe FakeCloudSetup.FolderCall("drive-1:", "Lib/Sub")

            // A connection string has no folder part: its whole text is the remote.
            session.sync.configFlow.value = CloudSyncConfig(remote = ":s3,provider=AWS:")
            model.cloud.changeFolder()
            advanceUntilIdle()
            setup.folderLists.last() shouldBe FakeCloudSetup.FolderCall(":s3,provider=AWS:", "")
        }

    @Test
    fun startingAgainMidSignInStopsTheSignInFirst() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            setup.answerGate = CompletableDeferred()
            setup.beginResult = { question("config_is_local", "oauth-state") }
            val model = vm(session)
            model.cloud.start()
            advanceUntilIdle()
            model.cloud.chooseProvider(provider(options = emptyList()))
            model.cloud.submit()
            runCurrent()
            (model.cloudState.setup is CloudSetupState.SigningIn) shouldBe true

            model.cloud.start()
            advanceUntilIdle()

            setup.stopOauthCalls shouldBe 1
            model.cloudState.setup shouldBe CloudSetupState.Providers(emptyList())
        }

    @Test
    fun theScheduleIsTheLibrarysOwnAndAFailedWriteIsReported() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.sync.configFlow.value = CloudSyncConfig(remote = "drive-1:Lib")
            val model = vm(session)
            val messages = collecting(model.messages.map { it.text })

            model.setCloudAutomatic(true)
            model.setCloudInterval(60)
            advanceUntilIdle()
            model.cloudState.config shouldBe
                CloudSyncConfig(enabled = true, remote = "drive-1:Lib", intervalMinutes = 60)

            session.sync.configureFailure = IllegalStateException("disk full")
            model.setCloudInterval(30)
            advanceUntilIdle()
            messages shouldContainExactly listOf(failure(Res.string.common_save_setting_failed, "disk full"))
        }

    @Test
    fun creatingAFolderOpensIt() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            val chosen = provider(options = emptyList())
            setup.beginResult = { SetupStep.Done("drive") }
            setup.foldersResult = { emptyList() }
            val model = vm(session)

            model.cloud.start()
            advanceUntilIdle()
            model.cloud.chooseProvider(chosen)
            model.cloud.submit()
            advanceUntilIdle()

            model.cloud.createFolder("NoPapersNeeded")
            advanceUntilIdle()

            setup.madeFolders.single() shouldBe FakeCloudSetup.FolderCall("drive:", "NoPapersNeeded")
            // "Use this folder" then means the folder just made, not the top level beside it.
            setup.folderLists.last() shouldBe FakeCloudSetup.FolderCall("drive:", "NoPapersNeeded")
            (model.cloudState.setup as CloudSetupState.Folders).usable shouldBe true
        }

    @Test
    fun cancellingABrowserSignInStopsIt() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            val chosen = provider(options = emptyList())
            setup.answerGate = CompletableDeferred()
            setup.beginResult = { question("config_is_local", "oauth-state") }
            setup.oauthStatusResult = OAuthStatus(running = true, authUrl = "http://127.0.0.1:53682/auth")
            val model = vm(session)

            model.cloud.start()
            advanceUntilIdle()
            model.cloud.chooseProvider(chosen)
            model.cloud.submit()
            runCurrent()

            model.cloud.cancel()
            advanceUntilIdle()

            model.cloudState.setup.shouldBeNull()
            setup.stopOauthCalls shouldBe 1
        }

    @Test
    fun aFailedSetupSurfacesAndRetryReloadsTheProviders() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            setup.providersFailure = IllegalStateException("engine down")
            val model = vm(session)

            model.cloud.start()
            advanceUntilIdle()
            model.cloudState.setup shouldBe CloudSetupState.Failed(SetupFailure.ENGINE, "engine down")

            setup.providersFailure = null
            val chosen = provider()
            setup.providersResult = listOf(chosen)
            model.cloud.start()
            advanceUntilIdle()
            model.cloudState.setup shouldBe CloudSetupState.Providers(listOf(chosen))
        }

    @Test
    fun withoutASetupFacadeTheWizardIsUnavailable() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)

            model.cloud.available shouldBe false
            model.cloud.start()
            advanceUntilIdle()
            model.cloudState.setup.shouldBeNull()
        }

    @Test
    fun disconnectForgetsTheRemoteItsSignInAndTheAutomaticToggle() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val setup = FakeCloudSetup()
            session.sync.setup = setup
            session.sync.configFlow.value = CloudSyncConfig(enabled = true, remote = "drive-1:NoPapersNeeded")
            val model = vm(session)

            model.cloud.disconnect()
            advanceUntilIdle()

            session.sync.configFlow.value shouldBe CloudSyncConfig(enabled = false, remote = null)
            // The remote the wizard made for this library goes from the engine's configuration,
            // and with it the sign-in token.
            setup.deletedRemotes shouldContainExactly listOf("drive-1")
            model.cloudState.setup.shouldBeNull()
        }

    private fun provider(
        type: String = "drive",
        name: String = "Google Drive",
        options: List<RcloneOption> =
            listOf(
                RcloneOption(
                    name = "client_id",
                    help = "Client id",
                    type = "string",
                    required = true,
                    defaultValue = "default-id",
                ),
            ),
        browserSignIn: Boolean = false,
    ) = RcloneProvider(
        type = type,
        name = name,
        description = "$name storage",
        options = options,
        browserSignIn = browserSignIn,
    )

    private fun question(
        option: String,
        state: String,
        type: String = "string",
    ) = SetupStep.Question(
        remoteName = "drive",
        state = state,
        option = RcloneOption(name = option, help = "$option help", type = type),
    )
}
