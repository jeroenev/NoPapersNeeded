package dev.npn.core.app

import dev.npn.core.mail.MailAccount
import dev.npn.core.mail.MailPostAction
import dev.npn.core.mail.MailSecurity
import dev.npn.core.mail.MailStartPoint
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The account as one synced setting per field: two devices that change different fields both keep
 * their change, a value from a later build is not overwritten unless the user changes that field,
 * and an account an earlier build stored as one JSON value still reads.
 */
class MailAccountSettingsTest {
    private val account =
        MailAccount(host = "mail.example.com", username = "me@example.com", postAction = MailPostAction.MARK_READ)

    /** The settings a save of [next] over [settings] leaves, as the journal would hold them. */
    private fun saved(
        settings: Map<String, String>,
        next: MailAccount,
    ): Map<String, String> = settings + MailAccountSettings.changes(settings::get, next)

    @Test
    fun twoDevicesChangingDifferentFieldsBothKeepTheirChange() {
        val base = saved(emptyMap(), account)
        val folderChange = MailAccountSettings.changes(base::get, account.copy(folder = "Invoices"))
        val intervalChange = MailAccountSettings.changes(base::get, account.copy(pollIntervalMinutes = 60))

        assertEquals(setOf(LibrarySettingKeys.MAIL_FOLDER), folderChange.keys)
        assertEquals(setOf(LibrarySettingKeys.MAIL_POLL_MINUTES), intervalChange.keys)
        val merged = base + folderChange + intervalChange
        assertEquals(
            account.copy(folder = "Invoices", pollIntervalMinutes = 60),
            MailAccountSettings.decode(merged::get),
        )
    }

    @Test
    fun aValueFromALaterBuildIsReadAsTheDefaultAndNotWrittenBack() {
        val base = saved(emptyMap(), account) + (LibrarySettingKeys.MAIL_SECURITY to "QUANTUM_TLS")
        val read = MailAccountSettings.decode(base::get)
        assertEquals(MailSecurity.SSL, read.security)

        val changes = MailAccountSettings.changes(base::get, read.copy(folder = "Invoices"))

        assertEquals(setOf(LibrarySettingKeys.MAIL_FOLDER), changes.keys)
    }

    @Test
    fun theAccountAnEarlierBuildStoredReadsAndTheFirstSaveWritesEveryField() {
        val legacy =
            mapOf(
                LibrarySettingKeys.MAIL_ACCOUNT to
                    """{"host":"mail.example.com","port":143,"security":"STARTTLS","username":"me",""" +
                    """"postAction":"DELETE","allowPlain":true,"startFrom":"EVERYTHING","saveEmailAsPdf":true}""",
            )
        val read = MailAccountSettings.decode(legacy::get)

        // The dropped "move to the trash" reads as keeping the message.
        assertEquals(
            MailAccount(
                host = "mail.example.com",
                port = 143,
                security = MailSecurity.STARTTLS,
                username = "me",
                postAction = MailPostAction.KEEP,
                startFrom = MailStartPoint.EVERYTHING,
                saveEmailAsPdf = true,
            ),
            read,
        )
        val first = saved(legacy, read.copy(folder = "Invoices"))
        val written = MailAccountSettings.changes(legacy::get, read.copy(folder = "Invoices")).keys
        assertEquals(11, written.size)
        // From then on the earlier value no longer decides anything.
        val changedLegacy = first + (LibrarySettingKeys.MAIL_ACCOUNT to """{"host":"other.example.com"}""")
        assertEquals(read.copy(folder = "Invoices"), MailAccountSettings.decode(changedLegacy::get))
    }

    @Test
    fun aFieldClearedHereIsNotReadFromTheEarlierValueAgain() {
        val legacy =
            mapOf(
                LibrarySettingKeys.MAIL_ACCOUNT to
                    """{"host":"mail.example.com","username":"me","moveToFolder":"Done"}""",
            )
        val first = saved(legacy, MailAccountSettings.decode(legacy::get).copy(moveToFolder = ""))
        assertEquals("", MailAccountSettings.decode(first::get).moveToFolder)
    }
}
