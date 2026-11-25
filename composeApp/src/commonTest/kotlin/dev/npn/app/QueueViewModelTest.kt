package dev.npn.app

import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.queue.ActivityMail
import dev.npn.app.ui.queue.ActivitySync
import dev.npn.app.ui.queue.QueueViewModel
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailCheckError
import dev.npn.core.mail.MailCheckReport
import dev.npn.core.mail.MailFailureReason
import dev.npn.core.mail.MailSecretFailure
import dev.npn.core.mail.MailState
import dev.npn.core.sync.CloudSyncConfig
import dev.npn.core.sync.SyncFailure
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncRunSummary
import dev.npn.core.sync.SyncStatus
import dev.npn.core.sync.TransportAvailability
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.queue_edit_run_discard_failed
import no_papers_needed.composeapp.generated.resources.queue_sync_not_configured
import no_papers_needed.composeapp.generated.resources.queue_sync_now_failed
import no_papers_needed.composeapp.generated.resources.queue_sync_unavailable
import kotlin.test.Test
import kotlin.test.assertEquals

class QueueViewModelTest : MainDispatcherTest() {
    @Test
    fun retryRequeuesTheFailedDocumentThroughOcrFacade() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val vm = QueueViewModel(session, FakeSettingsRepository())

            vm.retry("failed-doc")
            advanceUntilIdle()

            assertEquals(listOf("failed-doc"), session.ocr.reOcrIds)
        }

    @Test
    fun discardingAnEditRunDropsItQuietly() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val vm = QueueViewModel(session, FakeSettingsRepository())
            val messages = collecting(vm.messages.map { it.text })

            vm.discardEditRun("run")
            advanceUntilIdle()

            session.aiEditQueue.discarded shouldContainExactly listOf("run")
            messages.shouldBeEmpty()
        }

    /** The run stays on screen when the delete fails, so the user must be told why it did not go. */
    @Test
    fun aDiscardThatFailsSaysWhy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.aiEditQueue.discardFailure = IllegalStateException("database is locked")
            val vm = QueueViewModel(session, FakeSettingsRepository())
            val messages = collecting(vm.messages.map { it.text })

            vm.discardEditRun("run")
            advanceUntilIdle()

            messages shouldContainExactly
                listOf(failure(Res.string.queue_edit_run_discard_failed, "database is locked"))
        }

    /** Activity shows the cloud sync only for a library with a cloud folder on this device. */
    @Test
    fun theSyncRowShowsOnlyForALibraryWithACloudFolder() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val vm = QueueViewModel(session, FakeSettingsRepository())
            collecting(vm.sync)
            advanceUntilIdle()
            vm.sync.value shouldBe null

            val failedRun = SyncRunSummary(startedAt = 1L, finishedAt = 2L, failure = SyncFailure.REMOTE_UNREACHABLE)
            session.sync.configFlow.value = CloudSyncConfig(remote = "drive:library")
            session.sync.statusFlow.value = SyncStatus(lastRun = failedRun)
            advanceUntilIdle()

            vm.sync.value shouldBe ActivitySync(running = false, lastRun = failedRun)
            vm.sync.value?.failed shouldBe true
        }

    /** A pass that ran (or failed) shows on the row; only one that could not start is said. */
    @Test
    fun syncNowSaysOnlyWhyAPassCouldNotStart() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val vm = QueueViewModel(session, FakeSettingsRepository())
            val messages = collecting(vm.messages.map { it.text })

            session.sync.outcome = SyncOutcome.Failed(SyncFailure.REMOTE_UNREACHABLE)
            vm.syncNow()
            advanceUntilIdle()
            messages.shouldBeEmpty()

            session.sync.outcome = SyncOutcome.Unavailable(TransportAvailability.NotInstalled("/usr/bin/rclone"))
            vm.syncNow()
            advanceUntilIdle()
            session.sync.outcome = SyncOutcome.NotConfigured
            vm.syncNow()
            advanceUntilIdle()
            session.sync.syncNowFailure = IllegalStateException("engine crashed")
            vm.syncNow()
            advanceUntilIdle()

            session.sync.manualSyncs shouldBe 4
            messages shouldContainExactly
                listOf(
                    uiText(Res.string.queue_sync_unavailable),
                    uiText(Res.string.queue_sync_not_configured),
                    failure(Res.string.queue_sync_now_failed, "engine crashed"),
                )
        }

    /** The email row shows only for a configured account on a device that can import email. */
    @Test
    fun theEmailRowShowsForAConfiguredAccountAndCarriesItsProblem() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val vm = QueueViewModel(session, FakeSettingsRepository())
            collecting(vm.mail)
            advanceUntilIdle()
            vm.mail.value shouldBe null

            session.mail.state.value =
                MailState(
                    account = MailAccount(host = "mail.example.com", username = "someone"),
                    lastCheckedAtMillis = 5L,
                    lastReport = MailCheckReport(imported = 3),
                )
            advanceUntilIdle()
            vm.mail.value shouldBe
                ActivityMail(
                    checking = false,
                    cancelling = false,
                    progress = null,
                    lastCheckedAtMillis = 5L,
                    lastImported = 3,
                    problem = null,
                )

            session.mail.state.value =
                session.mail.state.value
                    .copy(lastError = MailFailureReason.NO_PASSWORD)
            advanceUntilIdle()
            vm.mail.value?.problem shouldBe MailFailureReason.NO_PASSWORD
            vm.mail.value?.failed shouldBe true

            // A secret store that cannot be read is a problem before any check has failed on it.
            session.mail.state.value =
                session.mail.state.value
                    .copy(lastError = null, credentialProblem = MailSecretFailure.UNAVAILABLE)
            advanceUntilIdle()
            vm.mail.value?.problem shouldBe MailFailureReason.SECRET_UNAVAILABLE

            // While a check runs it is not a problem yet: the row shows it in motion.
            session.mail.state.value =
                session.mail.state.value
                    .copy(checking = true)
            advanceUntilIdle()
            vm.mail.value?.failed shouldBe false

            session.mail.state.value =
                session.mail.state.value
                    .copy(transportAvailable = false)
            advanceUntilIdle()
            vm.mail.value shouldBe null
        }

    /** Check now and Cancel reach the email facet; a check that ran says nothing here. */
    @Test
    fun checkNowAndCancelReachTheMailFacet() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val vm = QueueViewModel(session, FakeSettingsRepository())
            val messages = collecting(vm.messages.map { it.text })
            session.mail.report = MailCheckReport(errors = listOf(MailCheckError(MailFailureReason.CONNECTION_FAILED)))

            vm.checkMailNow()
            vm.cancelMailCheck()
            advanceUntilIdle()

            session.mail.checks shouldBe 1
            session.mail.cancels shouldBe 1
            messages.shouldBeEmpty()
        }
}
