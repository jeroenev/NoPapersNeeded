package dev.npn.app

import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.settings.CloudSyncState
import dev.npn.app.ui.settings.RemoteListState
import dev.npn.app.ui.settings.SettingsViewModel
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncReport
import dev.npn.core.sync.SyncStatus
import dev.npn.core.sync.TransportAvailability
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The cloud sync corner of [SettingsViewModel]: probing the transfer engine, listing remotes and
 * asking for a pass, all against [FakeSyncFacade]. Split from [SettingsViewModelTest] to keep
 * either class within bounds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelCloudSyncTest : MainDispatcherTest() {
    /** A viewmodel over [session] whose pane state is collected, as the screen collects it. */
    private fun TestScope.vm(session: FakeLibrarySession): SettingsViewModel =
        settingsVm(session = session).also { collecting(it.paneState) }

    private val SettingsViewModel.cloud: CloudSyncState get() = paneState.value.cloud

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
            model.cloud.transport.shouldBeNull()

            // ...and the one for the finished path, which answers straight away.
            session.sync.availabilityGate = null
            session.sync.availability = TransportAvailability.Available("v1.70")
            model.probeTransport()
            advanceUntilIdle()
            model.cloud.transport shouldBe TransportAvailability.Available("v1.70")

            // The stale probe finishing late must not write over that answer.
            session.sync.availability = TransportAvailability.NotInstalled("/half/typed")
            gate.complete(Unit)
            advanceUntilIdle()
            model.cloud.transport shouldBe TransportAvailability.Available("v1.70")
            session.sync.probes shouldBe 2
        }

    @Test
    fun aNewProbeForgetsTheRemoteList() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            session.sync.remotes = listOf("drive", "nas")
            model.loadRemotes()
            advanceUntilIdle()
            model.cloud.remotes shouldBe RemoteListState(remotes = listOf("drive", "nas"))

            // The list came from the config file the old paths pointed at: gone the moment a new
            // probe is asked for, before it has answered.
            session.sync.availabilityGate = CompletableDeferred()
            model.probeTransport()
            runCurrent()
            model.cloud.remotes shouldBe RemoteListState()
        }

    @Test
    fun aFailedRemoteListingKeepsTheEngineStatus() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            session.sync.availability = TransportAvailability.Available("v1.70")
            model.probeTransport()
            advanceUntilIdle()

            session.sync.listRemotesFailure = IllegalStateException("config unreadable")
            model.loadRemotes()
            advanceUntilIdle()

            model.cloud.remotes.error shouldBe UiText.Raw("config unreadable")
            model.cloud.remotes.remotes
                .shouldBeNull()
            // rclone itself is fine; only its config could not be read.
            model.cloud.transport shouldBe TransportAvailability.Available("v1.70")
        }

    @Test
    fun aThrowingFacadeEndsAsAFailedOutcomeInsteadOfACrash() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            session.sync.syncNowFailure = IllegalStateException("engine exploded")

            model.syncNow()
            advanceUntilIdle()

            model.cloud.outcome shouldBe SyncOutcome.Failed("engine exploded")
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
            model.cloud.outcome shouldBe SyncOutcome.Completed(report)

            // An automatic pass starts: its result is what the status shows from here on.
            session.sync.statusFlow.value = SyncStatus(running = true)
            advanceUntilIdle()
            model.cloud.outcome.shouldBeNull()
            model.cloud.status.running shouldBe true
        }
}
