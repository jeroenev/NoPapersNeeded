package dev.npn.core.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MIME walking over a raw RFC 822 message: no server, no credentials, and explicit Content-Type
 * headers so Jakarta Mail cannot infer a fixture's parts as all text/plain.
 */
class MimeParserTest {
    private val pdfBytes = byteArrayOf(1, 2, 3, 4)

    private fun raw(): String =
        listOf(
            "From: Sender <sender@example.com>",
            "To: Me <me@example.com>",
            "Subject: Invoice March",
            "Date: Tue, 14 Nov 2023 22:13:20 +0000",
            "MIME-Version: 1.0",
            "Content-Type: multipart/mixed; boundary=\"BOUND\"",
            "",
            "--BOUND",
            "Content-Type: text/plain; charset=UTF-8",
            "Content-Transfer-Encoding: 7bit",
            "",
            "Hello",
            "World",
            "--BOUND",
            "Content-Type: application/pdf",
            "Content-Disposition: attachment; filename=\"Invoice March.pdf\"",
            "Content-Transfer-Encoding: base64",
            "",
            "AQIDBA==",
            "--BOUND",
            "Content-Type: application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "Content-Disposition: attachment; filename=\"notes.docx\"",
            "Content-Transfer-Encoding: base64",
            "",
            "BQYH",
            "--BOUND",
            "Content-Type: image/png",
            "Content-Disposition: inline; filename=\"logo.png\"",
            "Content-Transfer-Encoding: base64",
            "",
            "CQ==",
            "--BOUND--",
            "",
        ).joinToString("\r\n")

    private fun message(): MimeMessage =
        MimeMessage(
            Session.getInstance(Properties()),
            ByteArrayInputStream(raw().toByteArray(Charsets.UTF_8)),
        )

    @Test
    fun oversizedSupportedAttachmentFailsTheWholeMessage() {
        val content = "x".repeat(25 * 1024 * 1024 + 1)
        val raw =
            listOf(
                "MIME-Version: 1.0",
                "Content-Type: application/pdf",
                "Content-Disposition: attachment; filename=large.pdf",
                "",
                content,
            ).joinToString("\r\n")
        val message = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw.toByteArray()))
        assertFailsWith<Exception> { MimeParser.parse(1, message) }
        // The fetch reads it as this one message's failure, so the rest of the page still arrives.
        val read = MimeParser.parseOrFailed(1, message)
        assertEquals(MailFailureReason.FETCH_FAILED, read.readFailure)
        assertTrue(read.attachments.isEmpty())
    }

    @Test
    fun headersTextAndOnlySupportedAttachmentsAreRead() {
        val parsed = MimeParser.parse(42, message())
        assertEquals(42, parsed.uid)
        assertTrue(parsed.from.contains("sender@example.com"))
        assertTrue(parsed.to.contains("me@example.com"))
        assertEquals("Invoice March", parsed.subject)
        assertEquals(1_700_000_000_000, parsed.dateMillis)
        assertEquals("Hello\nWorld", parsed.bodyText)
        assertEquals(1, parsed.attachments.size)
        assertEquals("Invoice March.pdf", parsed.attachments.single().fileName)
        assertEquals("application/pdf", parsed.attachments.single().contentType)
        assertContentEquals(pdfBytes, parsed.attachments.single().bytes)
    }
}
