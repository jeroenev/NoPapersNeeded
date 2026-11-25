package dev.npn.core.mail

import jakarta.activation.DataHandler
import jakarta.mail.Session
import jakarta.mail.internet.MimeBodyPart
import jakarta.mail.internet.MimeMessage
import jakarta.mail.internet.MimeMultipart
import jakarta.mail.util.ByteArrayDataSource
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The parser's two passes, counted part by part. The envelope pass must decide from the structure
 * alone (an IMAP message has it from BODYSTRUCTURE, loaded for a whole batch at once); the download
 * pass must read only what it needs. A read here stands for a round trip to the server there.
 */
class MimeParserPassesTest {
    /** A part that counts every time its content is reached: a body fetch, over IMAP. */
    private class CountingPart : MimeBodyPart() {
        var reads = 0

        override fun getDataHandler(): DataHandler {
            reads++
            return super.getDataHandler()
        }
    }

    private fun text(
        body: String,
        subtype: String,
    ) = CountingPart().apply { setText(body, "UTF-8", subtype) }

    private fun file(
        name: String,
        type: String,
        bytes: ByteArray,
        disposition: String = "attachment",
        contentId: String? = null,
    ) = CountingPart().apply {
        dataHandler = DataHandler(ByteArrayDataSource(bytes, type))
        fileName = name
        this.disposition = disposition
        contentId?.let { contentID = "<$it>" }
    }

    private fun png(
        width: Int,
        height: Int,
    ): ByteArray =
        ByteArrayOutputStream()
            .also {
                ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", it)
            }.toByteArray()

    private fun message(vararg parts: CountingPart): MimeMessage =
        MimeMessage(Session.getInstance(Properties())).apply {
            subject = "Statement"
            setContent(MimeMultipart().apply { parts.forEach(::addBodyPart) })
            saveChanges()
            // Building the message reads its parts; only what the parser reads afterwards counts.
            parts.forEach { it.reads = 0 }
        }

    @Test
    fun theEnvelopeIsReadFromTheStructureWithoutTouchingAnyPart() {
        val plain = text("Your statement", "plain")
        val html = text("<p>Your statement</p>", "html")
        val pdf = file("statement.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        val envelope = MimeParser.envelopeOrFailed(1, message(plain, html, pdf))
        assertEquals("Statement", envelope.subject)
        assertEquals(listOf("statement.pdf"), envelope.attachments.map { it.fileName })
        assertEquals(listOf(0, 0, 0), listOf(plain.reads, html.reads, pdf.reads))
    }

    @Test
    fun aDownloadReadsTheAttachmentButNeitherTextNorHtmlUnlessNeeded() {
        val plain = text("Your statement", "plain")
        val html = text("<p>Your statement</p>", "html")
        val pdf = file("statement.pdf", "application/pdf", byteArrayOf(1, 2, 3))
        val message = message(plain, html, pdf)
        val downloaded = MimeParser.downloadOrFailed(1, message, withBody = false)
        assertEquals(listOf("statement.pdf"), downloaded.attachments.map { it.fileName })
        assertEquals("", downloaded.bodyText)
        assertEquals(0, plain.reads)
        assertEquals(0, html.reads)

        // The email PDF needs the text, and only then is it read; the HTML still is not.
        val withText = MimeParser.downloadOrFailed(1, message, withBody = true)
        assertEquals("Your statement", withText.bodyText)
        assertEquals(0, html.reads)
    }

    @Test
    fun theHtmlIsReadOnlyToCheckAnImageItMayShow() {
        val html = text("""<p><img src="cid:photo@example"></p>""", "html")
        val shown = file("photo.png", "image/png", png(800, 600), disposition = "inline", contentId = "photo@example")
        val downloaded = MimeParser.downloadOrFailed(1, message(html, shown), withBody = false)
        // The HTML shows it: part of the body, not a document.
        assertEquals(emptyList(), downloaded.attachments)
        assertTrue(html.reads > 0)
        assertEquals(0, shown.reads)
    }
}
