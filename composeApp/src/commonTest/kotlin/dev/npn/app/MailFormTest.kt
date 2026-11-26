package dev.npn.app

import dev.npn.app.ui.settings.MailForm
import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailDeviceChoices
import dev.npn.core.mail.MailFilter
import dev.npn.core.mail.MailSecurity
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The email pane's draft: each field the user has not changed follows changes saved on other
 * devices, a field they changed keeps their edit, and a draft that was saved counts as untouched
 * again.
 */
class MailFormTest {
    private val saved = MailAccount(host = "mail.example.com", username = "someone@example.com")
    private val enabled = MailDeviceChoices(enabledHere = true)

    /** The security dropdown with the port still at the old default, as the pane does it. */
    private fun MailForm.chooseStartTls() {
        portText = "143"
        draft = draft.copy(security = MailSecurity.STARTTLS)
    }

    @Test
    fun aSavedDraftFollowsLaterChangesFromOtherDevices() {
        val form = MailForm(saved, savedChoices = enabled)
        form.chooseStartTls()
        val submitted = form.whenSaved()
        // The save comes back as the saved account; then another device adds a filter.
        form.follow(submitted, enabled)
        val remote = submitted.copy(filters = listOf(MailFilter(from = "bank")))
        form.follow(remote, enabled)

        form.whenSaved() shouldBe remote
    }

    @Test
    fun aSuccessfulSaveMakesTheDraftTheSavedAccount() {
        val form = MailForm(saved, savedChoices = MailDeviceChoices())
        form.chooseStartTls()
        form.choices = enabled
        val submitted = form.whenSaved()

        form.saved(submitted, enabled)

        form.draft shouldBe submitted
        form.seed shouldBe submitted
        form.savedChoices shouldBe enabled
        // Untouched now: the next change from another device is taken in.
        val remote = submitted.copy(folder = "Invoices")
        form.follow(remote, enabled)
        form.whenSaved() shouldBe remote
    }

    /** Another device's change to another field is taken in; the field edited here keeps its edit. */
    @Test
    fun anEditedDraftKeepsItsEditsAndTakesInOtherFields() {
        val form = MailForm(saved, savedChoices = enabled)
        form.chooseStartTls()
        form.follow(saved.copy(pollIntervalMinutes = 30, host = "imap.example.com"), enabled)

        form.whenSaved().security shouldBe MailSecurity.STARTTLS
        form.whenSaved().port shouldBe 143
        form.whenSaved().pollIntervalMinutes shouldBe 30
        form.intervalText shouldBe "30"
        form.whenSaved().host shouldBe "imap.example.com"
    }

    @Test
    fun aPortBeingTypedIsNotOverwritten() {
        val form = MailForm(saved, savedChoices = enabled)
        form.portText = ""
        form.follow(saved.copy(port = 1993), enabled)

        form.portText shouldBe ""
    }

    @Test
    fun thisDevicesChoicesFollowUnlessChangedHere() {
        val form = MailForm(saved, savedChoices = MailDeviceChoices())
        form.choices = MailDeviceChoices(allowPlain = true)
        form.follow(saved, MailDeviceChoices(enabledHere = true))

        form.choices shouldBe MailDeviceChoices(enabledHere = true, allowPlain = true)
    }
}
