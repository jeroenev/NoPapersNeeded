package dev.npn.app

import dev.npn.app.ui.common.uiText
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailCheckError
import dev.npn.core.mail.MailCheckReport
import dev.npn.core.mail.MailDeviceChoices
import dev.npn.core.mail.MailFailureReason
import dev.npn.core.mail.MailSaveOutcome
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.email_check_cancelled
import no_papers_needed.composeapp.generated.resources.email_check_failed
import no_papers_needed.composeapp.generated.resources.email_check_report
import no_papers_needed.composeapp.generated.resources.email_reason_authentication_failed
import kotlin.test.Test

class SettingsMailTest : MainDispatcherTest() {
    @Test
    fun passwordDraftIsAcknowledgedOnlyAfterSuccess() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            var acknowledgements = 0
            session.mail.saveOutcome = MailSaveOutcome.AccountInvalid
            model.saveMailAccount(MailAccount(), "draft", MailDeviceChoices(enabledHere = true)) { acknowledgements++ }
            model.clearMailPassword { acknowledgements++ }
            runCurrent()
            acknowledgements shouldBe 0

            session.mail.saveOutcome = MailSaveOutcome.Saved
            model.saveMailAccount(MailAccount(), "draft", MailDeviceChoices(enabledHere = true)) { acknowledgements++ }
            model.clearMailPassword { acknowledgements++ }
            runCurrent()
            acknowledgements shouldBe 2
            // Whether this device checks travels with the save, as this device's own choice.
            session.mail.device shouldBe MailDeviceChoices(enabledHere = true)
        }

    /** The keyring is read for the pane when it opens, not at every start. */
    @Test
    fun openingThePaneLooksUpThePassword() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)

            model.mailPaneOpened()
            runCurrent()

            session.mail.lookUps shouldBe 1
        }

    /** Try again reaches the facet, and says how it went as Check now does. */
    @Test
    fun retryingPassedOverMailRunsACheck() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)

            model.retryPassedOverMail()
            runCurrent()

            session.mail.retries shouldBe 1
        }

    /**
     * Check now says why a check failed, that it was cancelled, or what it saw; a check that imported
     * documents leaves the saying to the app's own message, which has the way to them.
     */
    @Test
    fun checkNowReportsFailuresCancelsAndEmptyChecksButNotImports() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            val messages = collecting(model.messages.map { it.text })

            session.mail.report =
                MailCheckReport(errors = listOf(MailCheckError(MailFailureReason.AUTHENTICATION_FAILED)))
            model.checkMailNow()
            runCurrent()
            session.mail.report =
                MailCheckReport(messagesSeen = 4, imported = 1, cancelled = true, importedDocIds = listOf("d"))
            model.checkMailNow()
            runCurrent()
            session.mail.report = MailCheckReport(messagesSeen = 2, imported = 2, importedDocIds = listOf("a", "b"))
            model.checkMailNow()
            runCurrent()
            session.mail.report = MailCheckReport(messagesSeen = 3, skipped = 3)
            model.checkMailNow()
            runCurrent()

            messages shouldContainExactly
                listOf(
                    uiText(Res.string.email_check_failed, uiText(Res.string.email_reason_authentication_failed)),
                    uiText(Res.string.email_check_cancelled),
                    uiText(Res.string.email_check_report, 3, 0, 0, 3),
                )
        }

    @Test
    fun cancelReachesTheMailFacet() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = settingsVm(session = session)
            model.cancelMailCheck()
            session.mail.cancels shouldBe 1
        }
}
