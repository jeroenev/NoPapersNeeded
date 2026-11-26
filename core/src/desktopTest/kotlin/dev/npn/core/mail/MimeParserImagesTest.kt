package dev.npn.core.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Properties
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which image parts of a mail are documents. A newsletter's HTML body carries its logos and social
 * icons as parts of their own (a `multipart/related` beside the HTML, named by a generated hash and
 * given a Content-ID the HTML points at); importing those filled a real library with icon-sized
 * one-page PDFs. A photographed receipt sent as an attachment must still come through.
 */
class MimeParserImagesTest {
    private fun image(
        width: Int,
        height: Int,
        format: String = "png",
    ): String {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), format, out)
        return Base64.getMimeEncoder().encodeToString(out.toByteArray())
    }

    private fun part(
        type: String,
        name: String,
        body: String,
        disposition: String? = null,
        contentId: String? = null,
    ): List<String> =
        listOfNotNull(
            "Content-Type: $type; name=\"$name\"",
            disposition?.let { "Content-Disposition: $it; filename=\"$name\"" },
            contentId?.let { "Content-ID: <$it>" },
            "Content-Transfer-Encoding: base64",
            "",
            body,
        )

    /**
     * A job-alert newsletter as the user's real one was built: text and HTML alternatives inside a
     * related part with the images the HTML shows, then the mail's attachments beside it.
     */
    private fun newsletter(): MimeMessage {
        val html =
            """<html><body><img src="cid:hero@news.example"><p>New vacancies</p>""" +
                """<a href="https://x.example"><img src="CID:icon%40news.example"></a></body></html>"""
        val lines =
            listOf(
                "From: Jobs <jobs@example.com>",
                "To: Me <me@example.com>",
                "Subject: New vacancies",
                "MIME-Version: 1.0",
                "Content-Type: multipart/mixed; boundary=\"MIXED\"",
                "",
                "--MIXED",
                "Content-Type: multipart/related; boundary=\"RELATED\"",
                "",
                "--RELATED",
                "Content-Type: multipart/alternative; boundary=\"ALT\"",
                "",
                "--ALT",
                "Content-Type: text/plain; charset=UTF-8",
                "",
                "New vacancies",
                "--ALT",
                "Content-Type: text/html; charset=UTF-8",
                "",
                html,
                "--ALT--",
                "--RELATED",
                // Shown by the HTML: large enough to pass a size floor, so only the reference drops it.
            ) +
                part(
                    "image/png",
                    "4c9ef4f683e347b8b9b81b4157db77b3.png",
                    image(800, 600),
                    contentId = "hero@news.example",
                ) +
                listOf("--RELATED") +
                // Shown by the HTML, its reference percent-encoded and in upper case.
                part(
                    "image/png",
                    "650a892e975e430fad8ffa1ddd9c3188.png",
                    image(271, 52),
                    contentId = "icon@news.example",
                ) +
                listOf("--RELATED") +
                // A resource of the HTML that nothing references and no disposition makes an attachment.
                part(
                    "image/png",
                    "89baf917f7e94da4b8c8d2c74a777c05.png",
                    image(600, 600),
                    contentId = "unused@mail.example",
                ) +
                listOf("--RELATED") +
                // Explicitly attached, even though the sender's client nested it in the related part.
                part("image/jpeg", "scan.jpg", image(1000, 800, "jpg"), disposition = "attachment") +
                listOf("--RELATED--", "--MIXED") +
                // An attached social icon: a real attachment, but too small to be a document.
                part("image/png", "ba670fe4a99d4be1ba506b805bbfc606.png", image(100, 100), disposition = "attachment") +
                listOf("--MIXED") +
                // A photographed receipt, the image attachment the feature exists for.
                part("image/jpeg", "receipt.jpg", image(1200, 1600, "jpg"), disposition = "attachment") +
                listOf("--MIXED") +
                part("application/pdf", "invoice.pdf", "AQIDBA==", disposition = "attachment") +
                listOf("--MIXED--", "")
        return MimeMessage(
            Session.getInstance(Properties()),
            ByteArrayInputStream(lines.joinToString("\r\n").toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun theHtmlBodysImagesAndIconSizedImagesAreNotDocuments() {
        val parsed = MimeParser.read(1, newsletter())
        assertEquals(listOf("scan.jpg", "receipt.jpg", "invoice.pdf"), parsed.attachments.map { it.fileName })
    }

    @Test
    fun anImageThatCannotBeMeasuredIsKept() {
        // Undecodable bytes: the importer decides, as it did for every image before the size floor.
        val lines =
            listOf("MIME-Version: 1.0", "Content-Type: multipart/mixed; boundary=\"B\"", "", "--B") +
                part("image/tiff", "fax.tif", "AQIDBA==", disposition = "attachment") +
                listOf("--B--", "")
        val message =
            MimeMessage(
                Session.getInstance(Properties()),
                ByteArrayInputStream(lines.joinToString("\r\n").toByteArray()),
            )
        assertEquals(listOf("fax.tif"), MimeParser.read(1, message).attachments.map { it.fileName })
    }

    @Test
    fun anInlineImageIsAPhotoUnlessTheBodyShowsItOrItIsDecorationSized() {
        val html = """<html><body><img src="cid:banner@news.example"></body></html>"""
        val lines =
            listOf(
                "MIME-Version: 1.0",
                "Content-Type: multipart/mixed; boundary=\"M\"",
                "",
                "--M",
                "Content-Type: text/html; charset=UTF-8",
                "",
                html,
                "--M",
            ) + part("image/jpeg", "IMG_0042.jpg", image(1200, 1600, "jpg"), disposition = "inline") +
                listOf("--M") +
                part(
                    "image/png",
                    "banner.png",
                    image(800, 300),
                    disposition = "inline",
                    contentId = "banner@news.example",
                ) +
                listOf("--M") +
                part("image/png", "spacer.png", image(1, 1), disposition = "inline") +
                listOf("--M--", "")
        val message =
            MimeMessage(
                Session.getInstance(Properties()),
                ByteArrayInputStream(lines.joinToString("\r\n").toByteArray()),
            )
        assertEquals(listOf("IMG_0042.jpg"), MimeParser.read(1, message).attachments.map { it.fileName })
    }
}
