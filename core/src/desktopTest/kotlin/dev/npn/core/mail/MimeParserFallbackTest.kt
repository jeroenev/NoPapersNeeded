package dev.npn.core.mail

import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.Locale
import java.util.Properties
import javax.imageio.ImageIO
import javax.imageio.ImageReadParam
import javax.imageio.ImageReader
import javax.imageio.ImageTypeSpecifier
import javax.imageio.metadata.IIOMetadata
import javax.imageio.spi.IIORegistry
import javax.imageio.spi.ImageReaderSpi
import javax.imageio.stream.ImageInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A part the parser cannot make sense of costs only that part: a body in a charset Java does not
 * know, a malformed `cid:` link, an image whose header breaks its decoder. The rest of the message,
 * its PDF above all, is still read, rather than the whole email being passed over as unreadable.
 */
class MimeParserFallbackTest {
    private fun message(vararg parts: List<String>): MimeMessage {
        val lines =
            listOf(
                "From: shop@example.com",
                "Subject: Invoice",
                "MIME-Version: 1.0",
                "Content-Type: multipart/mixed; boundary=\"B\"",
                "",
            ) + parts.flatMap { listOf("--B") + it } + listOf("--B--", "")
        return MimeMessage(
            Session.getInstance(Properties()),
            ByteArrayInputStream(lines.joinToString("\r\n").toByteArray(Charsets.ISO_8859_1)),
        )
    }

    private val invoice =
        listOf(
            "Content-Type: application/pdf",
            "Content-Disposition: attachment; filename=\"invoice.pdf\"",
            "Content-Transfer-Encoding: base64",
            "",
            "AQIDBA==",
        )

    private fun related(vararg parts: List<String>): List<String> =
        listOf("Content-Type: multipart/related; boundary=\"R\"", "") +
            parts.flatMap { listOf("--R") + it } + listOf("--R--")

    private fun image(
        name: String,
        contentId: String,
        bytes: ByteArray,
        disposition: String = "inline",
    ) = listOf(
        "Content-Type: image/png",
        "Content-Disposition: $disposition; filename=\"$name\"",
        "Content-ID: <$contentId>",
        "Content-Transfer-Encoding: base64",
        "",
        Base64.getEncoder().encodeToString(bytes),
    )

    private fun html(body: String) =
        listOf("Content-Type: text/html; charset=UTF-8", "Content-Transfer-Encoding: 7bit", "", body)

    private fun png(side: Int): ByteArray =
        ByteArrayOutputStream()
            .also { out -> ImageIO.write(BufferedImage(side, side, BufferedImage.TYPE_INT_RGB), "png", out) }
            .toByteArray()

    @Test
    fun aBodyInAnUnknownCharsetIsReadAsUtf8AndThePdfIsKept() {
        val parsed =
            MimeParser.read(
                1,
                message(
                    listOf(
                        "Content-Type: text/plain; charset=x-no-such-charset",
                        "Content-Transfer-Encoding: 8bit",
                        "",
                        "Total: 12 EUR",
                    ),
                    invoice,
                ),
            )

        assertNull(parsed.readFailure, parsed.readFailureCause)
        assertEquals(listOf("invoice.pdf"), parsed.attachments.map { it.fileName })
        assertTrue("Total: 12 EUR" in parsed.bodyText, parsed.bodyText)
    }

    @Test
    fun anHtmlBodyInAnUnknownCharsetStillLetsTheImagesBeJudged() {
        val parsed =
            MimeParser.read(
                1,
                message(
                    related(
                        listOf(
                            "Content-Type: text/html; charset=x-no-such-charset",
                            "Content-Transfer-Encoding: 8bit",
                            "",
                            "<p>Your scan</p><img src=\"cid:logo\">",
                        ),
                        image("logo.png", "logo", png(300), disposition = "attachment"),
                    ),
                    invoice,
                ),
            )

        assertNull(parsed.readFailure, parsed.readFailureCause)
        // The HTML still read: the image it shows is part of its look, not a document.
        assertEquals(listOf("invoice.pdf"), parsed.attachments.map { it.fileName })
    }

    @Test
    fun aMalformedCidLinkIsKeptAsWrittenAndAPlusIsNotASpace() {
        val parsed =
            MimeParser.read(
                1,
                message(
                    related(
                        html("<img src=\"cid:broken%zz\"><img src=\"cid:a+b\">"),
                        image("logo.png", "a+b", png(300), disposition = "attachment"),
                    ),
                    invoice,
                ),
            )

        assertNull(parsed.readFailure, parsed.readFailureCause)
        // `cid:a+b` is the image's own Content-ID: shown by the HTML, so not a document.
        assertEquals(listOf("invoice.pdf"), parsed.attachments.map { it.fileName })
    }

    @Test
    fun anImageWhoseHeaderBreaksItsDecoderIsKeptUnmeasured() {
        val spi = ThrowingReaderSpi()
        val registry = IIORegistry.getDefaultInstance()
        registry.registerServiceProvider(spi, ImageReaderSpi::class.java)
        try {
            val parsed =
                MimeParser.read(
                    1,
                    message(image("page.png", "page", ThrowingReaderSpi.MAGIC, disposition = "attachment"), invoice),
                )

            assertNull(parsed.readFailure, parsed.readFailureCause)
            assertEquals(listOf("page.png", "invoice.pdf"), parsed.attachments.map { it.fileName })
        } finally {
            registry.deregisterServiceProvider(spi, ImageReaderSpi::class.java)
        }
    }

    /** A decoder that claims [MAGIC] and then fails with a runtime exception, as real ones do on bad headers. */
    private class ThrowingReaderSpi : ImageReaderSpi() {
        init {
            vendorName = "test"
            version = "1"
            names = arrayOf("throwing")
            inputTypes = arrayOf(ImageInputStream::class.java)
        }

        override fun canDecodeInput(source: Any?): Boolean {
            val stream = source as? ImageInputStream ?: return false
            val head = ByteArray(MAGIC.size)
            stream.mark()
            return try {
                stream.readFully(head)
                head.contentEquals(MAGIC)
            } catch (_: IOException) {
                false
            } finally {
                stream.reset()
            }
        }

        override fun createReaderInstance(extension: Any?): ImageReader = ThrowingReader(this)

        override fun getDescription(locale: Locale?): String = "A decoder that throws"

        companion object {
            val MAGIC = "BROKEN-IMAGE-HEADER".toByteArray()
        }
    }

    private class ThrowingReader(
        spi: ImageReaderSpi,
    ) : ImageReader(spi) {
        override fun getNumImages(allowSearch: Boolean): Int = 1

        override fun getWidth(imageIndex: Int): Int = error("corrupt header")

        override fun getHeight(imageIndex: Int): Int = error("corrupt header")

        override fun getImageTypes(imageIndex: Int): Iterator<ImageTypeSpecifier> =
            emptyList<ImageTypeSpecifier>().iterator()

        override fun getStreamMetadata(): IIOMetadata? = null

        override fun getImageMetadata(imageIndex: Int): IIOMetadata? = null

        override fun read(
            imageIndex: Int,
            param: ImageReadParam?,
        ): BufferedImage = error("corrupt header")
    }
}
