package dev.npn.core.mail

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MailAccountTest {
    private fun account(
        filters: List<MailFilter> = emptyList(),
        postAction: MailPostAction = MailPostAction.KEEP,
        moveToFolder: String = "",
    ) = MailAccount(
        host = "mail.example.com",
        username = "someone@example.com",
        folder = "INBOX",
        filters = filters,
        postAction = postAction,
        moveToFolder = moveToFolder,
    )

    private fun message(
        from: String = "sender@example.com",
        subject: String = "Invoice",
        attachments: List<MailAttachment> = emptyList(),
    ) = MailEnvelope(
        uid = 1,
        from = from,
        to = "me@example.com",
        subject = subject,
        dateMillis = 0,
        attachments = attachments.map { MailAttachmentInfo(it.fileName, it.contentType) },
    )

    @Test
    fun anEmptyFilterListMatchesEveryMessage() {
        assertTrue(account().matches(message()))
        assertTrue(
            account().matches(message(attachments = listOf(MailAttachment("a.txt", "text/plain", ByteArray(1))))),
        )
    }

    @Test
    fun anyFilterMatchingIsEnough() {
        val account = account(filters = listOf(MailFilter(from = "nobody"), MailFilter(subject = "invoice")))
        assertTrue(account.matches(message(subject = "INVOICE for March")))
        assertFalse(account.matches(message(subject = "A letter")))
    }

    @Test
    fun everySetFieldOfOneFilterMustMatch() {
        val filter = MailFilter(from = "billing", subject = "invoice")
        assertTrue(filter.matches(message(from = "Billing <billing@example.com>", subject = "Invoice 7")))
        assertFalse(filter.matches(message(from = "Billing <billing@example.com>", subject = "Receipt")))
        assertFalse(filter.matches(message(from = "other@example.com", subject = "Invoice 7")))
    }

    @Test
    fun anAttachmentFilterNeedsASupportedMatchingAttachment() {
        val pdf = MailAttachment("Invoice 7.pdf", "application/pdf", ByteArray(2))
        val doc =
            MailAttachment(
                "Invoice 7.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                ByteArray(2),
            )
        assertTrue(MailFilter(attachment = "*.pdf").matches(message(attachments = listOf(pdf))))
        assertTrue(MailFilter(attachment = "pdf").matches(message(attachments = listOf(pdf))))
        assertTrue(MailFilter(attachment = "invoice*").matches(message(attachments = listOf(pdf))))
        assertFalse(MailFilter(attachment = "*.pdf").matches(message(attachments = listOf(doc))))
    }

    @Test
    fun supportedAttachmentKindsCoverPdfAndTheImporterImages() {
        assertEquals(MailAttachmentKind.PDF, MailAttachment("a.pdf", null, ByteArray(0)).supportedKind())
        assertEquals(MailAttachmentKind.PDF, MailAttachment("a.bin", "application/pdf", ByteArray(0)).supportedKind())
        assertEquals(MailAttachmentKind.IMAGE, MailAttachment("scan.JPG", null, ByteArray(0)).supportedKind())
        assertEquals(MailAttachmentKind.IMAGE, MailAttachment("scan", "image/png", ByteArray(0)).supportedKind())
        assertNull(MailAttachment("notes.docx", null, ByteArray(0)).supportedKind())
        assertNull(MailAttachment("mail.html", "text/html", ByteArray(0)).supportedKind())
    }

    @Test
    fun thePasswordAndCursorKeyIsTheServerAndUsernameAlone() {
        val key = account().accountKey
        assertEquals(key, account().copy(port = 143, security = MailSecurity.STARTTLS).accountKey)
        assertEquals(key, account().copy(host = " MAIL.example.com ", username = " SOMEONE@example.com ").accountKey)
        assertFalse(key == account().copy(host = "imap.example.com").accountKey)
        assertFalse(key == account().copy(username = "you").accountKey)
    }

    @Test
    fun theDroppedMoveToTrashReadsAsKeep() {
        val decoded = MailAccountJson.decode("""{"host":"mail.example.com","username":"me","postAction":"DELETE"}""")
        assertEquals("mail.example.com", decoded.host)
        assertEquals(MailPostAction.KEEP, decoded.postAction)
    }

    @Test
    fun aMovePostActionNeedsItsFolder() {
        assertFalse(account(postAction = MailPostAction.MOVE).isConfigured)
        assertTrue(account(postAction = MailPostAction.MOVE, moveToFolder = "Processed").isConfigured)
    }

    @Test
    fun normalizationClampsTheIntervalAndDropsBlankFilters() {
        val normalized =
            MailAccount(
                host = "  mail.example.com ",
                username = " me ",
                folder = "  ",
                pollIntervalMinutes = 1,
                filters = listOf(MailFilter(from = "  "), MailFilter(subject = " invoice ")),
            ).normalized()
        assertEquals("mail.example.com", normalized.host)
        assertEquals("me", normalized.username)
        assertEquals(MailAccount.DEFAULT_FOLDER, normalized.folder)
        assertEquals(MailAccount.MIN_POLL_MINUTES, normalized.pollIntervalMinutes)
        assertEquals(listOf(MailFilter(subject = "invoice")), normalized.filters)
    }

    @Test
    fun theJsonSettingKeepsTheAccountAndNeverAPassword() {
        val stored =
            MailAccountJson.encode(
                account().copy(
                    saveEmailAsPdf = true,
                    filters = listOf(MailFilter(from = "x")),
                ),
            )
        assertFalse(stored.contains("password", ignoreCase = true))
        val decoded = MailAccountJson.decode(stored)
        assertTrue(decoded.saveEmailAsPdf)
        assertEquals(listOf(MailFilter(from = "x")), decoded.filters)
        assertEquals(MailAccount(), MailAccountJson.decode("not json"))
    }

    @Test
    fun anEnabledKeyAnEarlierBuildSyncedIsIgnored() {
        // Turning import on was once library data; one device's choice must not switch others on.
        val decoded = MailAccountJson.decode("""{"host":"mail.example.com","username":"me","enabled":true}""")
        assertEquals("mail.example.com", decoded.host)
        assertFalse(MailAccountJson.encode(decoded).contains("enabled"))
    }

    @Test
    fun theEmailPdfNameIsStableAndSafe() {
        val message =
            MailMessage(
                uid = 1,
                from = "sender@example.com",
                to = "me@example.com",
                subject = "../../March/Invoice \u0000 " + "x".repeat(300),
                dateMillis = 0,
                bodyText = "",
                attachments = emptyList(),
            )
        val name = emailPdfFileName(message)
        assertEquals(name, emailPdfFileName(message))
        assertFalse(name.contains('\\'))
        assertFalse(name.contains('/'))
        assertFalse(name.contains("\u0000"))
        assertTrue(name.endsWith(".pdf"))
        assertTrue(name.length <= 124)
    }

    @Test
    fun anAccountSavedBeforeStartPointsReadsWithTheDefaultStart() {
        val stored = """{"host":"mail.example.com","username":"someone@example.com","enabled":true}"""
        val decoded = MailAccountJson.decode(stored)
        assertEquals("mail.example.com", decoded.host)
        assertEquals(MailStartPoint.LAST_30_DAYS, decoded.startFrom)
    }

    @Test
    fun aStartPointFromALaterBuildKeepsTheRestOfTheAccount() {
        val stored = """{"host":"mail.example.com","username":"someone@example.com","startFrom":"LAST_DECADE"}"""
        val decoded = MailAccountJson.decode(stored)
        assertEquals("mail.example.com", decoded.host)
        assertEquals(MailStartPoint.LAST_30_DAYS, decoded.startFrom)
    }

    @Test
    fun theStartPointRoundTripsThroughTheSetting() {
        val account = account().copy(startFrom = MailStartPoint.EVERYTHING)
        assertEquals(MailStartPoint.EVERYTHING, MailAccountJson.decode(MailAccountJson.encode(account)).startFrom)
    }

    @Test
    fun startPointsAreOrderedByHowFarBackTheyRead() {
        assertTrue(MailStartPoint.EVERYTHING.reachesFurtherBackThan(MailStartPoint.LAST_YEAR))
        assertTrue(MailStartPoint.LAST_YEAR.reachesFurtherBackThan(MailStartPoint.LAST_30_DAYS))
        assertTrue(MailStartPoint.LAST_30_DAYS.reachesFurtherBackThan(MailStartPoint.NEW_ONLY))
        assertFalse(MailStartPoint.NEW_ONLY.reachesFurtherBackThan(MailStartPoint.NEW_ONLY))
    }
}
