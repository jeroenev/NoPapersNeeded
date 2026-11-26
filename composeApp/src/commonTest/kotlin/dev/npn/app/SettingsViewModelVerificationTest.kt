package dev.npn.app

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.settings.SettingsViewModel
import dev.npn.core.app.LibraryVerificationProblem
import dev.npn.core.app.LibraryVerificationProgress
import dev.npn.core.app.LibraryVerificationReport
import dev.npn.core.app.VerificationPhase
import dev.npn.core.app.VerificationProblemKind
import dev.npn.core.sync.ConflictKind
import dev.npn.core.sync.SyncConflict
import dev.npn.core.sync.SyncHealth
import dev.npn.core.sync.SyncReport
import dev.npn.core.sync.SyncRunSummary
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_verify_cancelled
import no_papers_needed.composeapp.generated.resources.common_settings_verify_copy_failed
import no_papers_needed.composeapp.generated.resources.common_settings_verify_failed
import kotlin.test.Test

/** The library check as the settings screen drives it: progress, a result, cancel and failure. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelVerificationTest : MainDispatcherTest() {
    private fun TestScope.vm(session: FakeLibrarySession): SettingsViewModel =
        settingsVm(session = session).also { collecting(it.paneState) }

    private fun report(): LibraryVerificationReport =
        LibraryVerificationReport(
            startedAt = 1_000,
            durationMillis = 20,
            documentsChecked = 2,
            objectsChecked = 3,
            problems =
                listOf(
                    LibraryVerificationProblem(
                        kind = VerificationProblemKind.DOCUMENT_MISSING,
                        docId = "a".repeat(64),
                        fileName = "Invoice.pdf",
                        relPath = "Invoice.pdf",
                    ),
                ),
        )

    @Test
    fun conflictDocumentsFollowEditsAndRemoval() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val conflict = SyncConflict("a", "a.pdf", ConflictKind.TARGET_OCCUPIED)
            val report = SyncReport(startedAt = 1, durationMillis = 1, conflicts = listOf(conflict))
            session.sync.healthFlow.value = SyncHealth(lastPass = SyncRunSummary(1, 2, report))
            session.documents.documents.value = listOf(listItem(doc("a", title = "Before")))
            val model = vm(session)
            advanceUntilIdle()
            model.paneState.value.cloud.conflictDocuments["a"]
                ?.title shouldBe "Before"

            session.documents.documents.value = listOf(listItem(doc("a", title = "After")))
            advanceUntilIdle()
            model.paneState.value.cloud.conflictDocuments["a"]
                ?.title shouldBe "After"

            session.documents.documents.value = emptyList()
            advanceUntilIdle()
            model.paneState.value.cloud.conflictDocuments shouldBe emptyMap()
        }

    @Test
    fun aCheckReportsProgressAndThenItsResult() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            val progress = LibraryVerificationProgress(VerificationPhase.DOCUMENTS, 1, 2)
            session.maintenance.verifyProgress = listOf(progress)
            session.maintenance.verificationReport = report()

            model.verifyLibrary()
            advanceUntilIdle()

            session.maintenance.verificationRuns shouldBe 1
            val state = model.paneState.value.verification
            state.running shouldBe false
            state.progress.shouldBeNull()
            state.report shouldBe session.maintenance.verificationReport
        }

    @Test
    fun aCheckOutlivesTheScreenThatStartedItAndItsResultWaitsForTheNext() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val gate = CompletableDeferred<Unit>()
            session.maintenance.verifyGate = gate
            session.maintenance.verificationReport = report()
            val first = vm(session)
            first.verifyLibrary()
            runCurrent()
            // The screen goes away, as navigating off settings clears its viewmodel.
            first.viewModelScope.cancel()

            gate.complete(Unit)
            advanceUntilIdle()
            val next = vm(session)
            advanceUntilIdle()

            session.maintenance.verificationRuns shouldBe 1
            next.paneState.value.verification.report shouldBe session.maintenance.verificationReport
        }

    @Test
    fun cancellingARunDropsItsProgressAndSaysSo() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            val gate = CompletableDeferred<Unit>()
            session.maintenance.verifyGate = gate
            val messages = collecting(model.messages.map { it.text })

            model.verifyLibrary()
            runCurrent()
            model.paneState.value.verification.running shouldBe true

            model.cancelLibraryVerification()
            advanceUntilIdle()

            model.paneState.value.verification.running shouldBe false
            model.paneState.value.verification.report
                .shouldBeNull()
            messages shouldBe listOf(uiText(Res.string.common_settings_verify_cancelled))
            gate.complete(Unit)
        }

    @Test
    fun aFailedRunIsReportedAndLeavesNoReport() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            session.maintenance.verifyFailure = { error("library is on fire") }
            val messages = collecting(model.messages.map { it.text })

            model.verifyLibrary()
            advanceUntilIdle()

            model.paneState.value.verification.running shouldBe false
            model.paneState.value.verification.report
                .shouldBeNull()
            messages.single() shouldBe failure(Res.string.common_settings_verify_failed, "library is on fire")
        }

    @Test
    fun dismissingTheResultKeepsThePaneButDropsTheDialog() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            session.maintenance.verificationReport = report()

            model.verifyLibrary()
            advanceUntilIdle()
            model.paneState.value.verification.report shouldBe session.maintenance.verificationReport

            model.dismissLibraryVerification()
            advanceUntilIdle()
            model.paneState.value.verification.report
                .shouldBeNull()
            model.paneState.value.verification.running shouldBe false
        }

    @Test
    fun copyingTheOpenReportSaysSoWhereTheUserIsLooking() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.maintenance.verificationReport = report()
            val copied = mutableListOf<String>()
            val modelWithCopy = settingsVm(session = session, copy = { copied += it })
            collecting(modelWithCopy.paneState)

            modelWithCopy.verifyLibrary()
            advanceUntilIdle()
            modelWithCopy.copyLibraryVerificationReport("a report")
            advanceUntilIdle()

            copied shouldBe listOf("a report")
            modelWithCopy.paneState.value.verification.reportCopied shouldBe true
        }

    @Test
    fun aFailedCopyIsReportedInsteadOfClaimingTheReportWasCopied() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.maintenance.verificationReport = report()
            val model = settingsVm(session = session, copy = { error("clipboard closed") })
            collecting(model.paneState)
            val messages = collecting(model.messages.map { it.text })

            model.copyLibraryVerificationReport("a report")
            advanceUntilIdle()

            messages.single() shouldBe failure(Res.string.common_settings_verify_copy_failed, "clipboard closed")
            model.paneState.value.verification.reportCopied shouldBe false
        }

    @Test
    fun cancellingBeforeTheJobEverStartsStillClearsTheProgress() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            val gate = CompletableDeferred<Unit>()
            session.maintenance.verifyGate = gate

            model.verifyLibrary()
            // No runCurrent(): the job exists but has not run its body yet.
            model.cancelLibraryVerification()

            model.paneState.value.verification.running shouldBe false
            model.paneState.value.verification.progress
                .shouldBeNull()
            gate.complete(Unit)
        }

    @Test
    fun aSecondStartWhileOneRunsDoesNotStartAnother() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session)
            val gate = CompletableDeferred<Unit>()
            session.maintenance.verifyGate = gate

            model.verifyLibrary()
            runCurrent()
            model.verifyLibrary()
            runCurrent()

            session.maintenance.verificationRuns shouldBe 1
            gate.complete(Unit)
            advanceUntilIdle()
            model.paneState.value.verification.report
                ?.problems
                .orEmpty()
                .shouldBeEmpty()
        }
}
