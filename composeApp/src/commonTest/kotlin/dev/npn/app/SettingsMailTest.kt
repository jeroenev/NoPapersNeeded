package dev.npn.app

import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailSaveOutcome
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class SettingsMailTest : MainDispatcherTest() {
    @Test
    fun passwordDraftIsAcknowledgedOnlyAfterSuccess() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = settingsVm(session = session)
        var acknowledgements = 0
        session.mail.saveOutcome = MailSaveOutcome.AccountInvalid
        model.saveMailAccount(MailAccount(), "draft") { acknowledgements++ }
        model.clearMailPassword { acknowledgements++ }
        runCurrent()
        acknowledgements shouldBe 0

        session.mail.saveOutcome = MailSaveOutcome.Saved
        model.saveMailAccount(MailAccount(), "draft") { acknowledgements++ }
        model.clearMailPassword { acknowledgements++ }
        runCurrent()
        acknowledgements shouldBe 2
    }
}
