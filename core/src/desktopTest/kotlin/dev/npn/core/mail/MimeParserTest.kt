package dev.npn.core.mail

import jakarta.mail.FolderClosedException
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.io.ByteArrayInputStream
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Everything of [message] at once, its text included, as a download for the email PDF reads it. */
internal fun MimeParser.read(
    uid: Long,
    message: jakarta.mail.Message,
): MailMessage = downloadOrFailed(uid, message, withBody = true)

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
            "Message-ID:  <invoice-4711@mail.example.com> ",
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
            // A 1x1 PNG: an inline image too small to be a document.
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==",
            "--BOUND--",
            "",
        ).joinToString("\r\n")

    private fun message(): MimeMessage =
        MimeMessage(
            Session.getInstance(Properties()),
            ByteArrayInputStream(raw().toByteArray(Charsets.UTF_8)),
        )

    private fun mixed(vararg parts: List<String>): MimeMessage {
        val lines =
            listOf(
                "Subject: Secret subject",
                "MIME-Version: 1.0",
                "Content-Type: multipart/mixed; boundary=\"B\"",
                "",
            ) +
                parts.flatMap { listOf("--B") + it } + listOf("--B--", "")
        return MimeMessage(
            Session.getInstance(Properties()),
            ByteArrayInputStream(lines.joinToString("\r\n").toByteArray()),
        )
    }

    private fun pdfPart(
        name: String,
        body: String = "AQIDBA==",
        disposition: String = "attachment",
        encoding: String = "base64",
    ) = listOf(
        "Content-Type: application/pdf",
        "Content-Disposition: $disposition; filename=\"$name\"",
        "Content-Transfer-Encoding: $encoding",
        "",
        body,
    )

    @Test
    fun anAttachmentOverTheCapIsLeftOutAndTheRestOfTheMessageIsRead() {
        val message =
            mixed(pdfPart("large.pdf", "x".repeat(25 * 1024 * 1024 + 1), encoding = "7bit"), pdfPart("small.pdf"))
        val read = MimeParser.read(1, message)
        assertEquals(null, read.readFailure)
        assertEquals(listOf("small.pdf"), read.attachments.map { it.fileName })
        assertEquals(listOf(MailDroppedPart.TOO_LARGE), read.droppedParts)
    }

    @Test
    fun aPartThatCannotBeDecodedIsLeftOutAndTheRestOfTheMessageIsRead() {
        val message = mixed(pdfPart("broken.pdf", encoding = "x-unknown"), pdfPart("good.pdf"))
        val read = MimeParser.read(1, message)
        assertEquals(listOf("good.pdf"), read.attachments.map { it.fileName })
        assertEquals(listOf(MailDroppedPart.UNREADABLE), read.droppedParts)
    }

    @Test
    fun aMessageThatCannotBeParsedIsUnreadableWithItsCauseNamedButNothingOfItsContent() {
        val raw =
            listOf(
                "Subject: Secret subject",
                "MIME-Version: 1.0",
                "Content-Type: multipart/mixed; boundary=\"B\"",
                "Content-Transfer-Encoding: x-unknown",
                "",
                "--B--",
            ).joinToString("\r\n")
        val message = MimeMessage(Session.getInstance(Properties()), ByteArrayInputStream(raw.toByteArray()))
        val read = MimeParser.read(7, message)
        assertEquals(MailFailureReason.MESSAGE_UNREADABLE, read.readFailure)
        val cause = assertNotNull(read.readFailureCause)
        assertTrue(cause.isNotBlank())
        assertFalse(cause.contains("Secret"))
        assertTrue(cause.split(" <- ").all { name -> name.all { it.isLetterOrDigit() } })
    }

    @Test
    fun aLostConnectionFailsTheFetchInsteadOfMarkingTheMessageUnreadable() {
        val message =
            object : MimeMessage(Session.getInstance(Properties())) {
                override fun getContent(): Any = throw FolderClosedException(null)
            }
        assertFailsWith<FolderClosedException> { MimeParser.read(1, message) }
    }

    @Test
    fun aPdfSentInlineIsImported() {
        // Apple Mail and iOS Mail send attachments as "inline; filename=…".
        val read = MimeParser.read(1, mixed(pdfPart("Invoice.pdf", disposition = "inline")))
        assertEquals(listOf("Invoice.pdf"), read.attachments.map { it.fileName })
    }

    @Test
    fun headersTextAndOnlySupportedAttachmentsAreRead() {
        val parsed = MimeParser.read(42, message())
        assertEquals(42, parsed.uid)
        assertEquals("invoice-4711@mail.example.com", parsed.messageId)
        assertEquals("invoice-4711@mail.example.com", MimeParser.envelopeOrFailed(42, message()).messageId)
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
