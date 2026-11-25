package dev.npn.app

import dev.npn.app.ui.settings.MailForm
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailFilter
import dev.npn.core.mail.MailSecurity
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The email pane's draft: an untouched one follows changes saved on other devices, an edited one
 * keeps its edits, and a draft that was saved counts as untouched again.
 */
class MailFormTest {
    private val saved = MailAccount(host = "mail.example.com", username = "someone@example.com")

    /** The security dropdown with the port still at the old default, as the pane does it. */
    private fun MailForm.chooseStartTls() {
        portText = "143"
        draft = draft.copy(security = MailSecurity.STARTTLS)
    }

    @Test
    fun aSavedDraftFollowsLaterChangesFromOtherDevices() {
        val form = MailForm(saved, savedEnabledHere = true)
        form.chooseStartTls()
        val submitted = form.whenSaved()
        // The save comes back as the saved account; then another device adds a filter.
        form.follow(submitted, enabled = true)
        val remote = submitted.copy(filters = listOf(MailFilter(from = "bank")))
        form.follow(remote, enabled = true)

        form.whenSaved() shouldBe remote
    }

    @Test
    fun aSuccessfulSaveMakesTheDraftTheSavedAccount() {
        val form = MailForm(saved, savedEnabledHere = false)
        form.chooseStartTls()
        form.enabledHere = true
        val submitted = form.whenSaved()

        form.saved(submitted, enabled = true)

        form.draft shouldBe submitted
        form.seed shouldBe submitted
        form.savedEnabledHere shouldBe true
        // Untouched now: the next change from another device is taken in.
        val remote = submitted.copy(folder = "Invoices")
        form.follow(remote, enabled = true)
        form.whenSaved() shouldBe remote
    }

    @Test
    fun anEditedDraftKeepsItsEditsWhenAnotherDeviceSaves() {
        val form = MailForm(saved, savedEnabledHere = true)
        form.chooseStartTls()
        form.follow(saved.copy(pollIntervalMinutes = 30), enabled = true)

        form.whenSaved().security shouldBe MailSecurity.STARTTLS
        form.whenSaved().port shouldBe 143
    }
}
