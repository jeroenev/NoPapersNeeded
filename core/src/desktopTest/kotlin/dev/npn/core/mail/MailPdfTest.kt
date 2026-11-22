package dev.npn.core.mail

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MailPdfTest {
    private val message =
        MailMessage(
            uid = 7,
            from = "Sender <sender@example.com>",
            to = "Me <me@example.com>",
            subject = "Invoice March",
            dateMillis = 1_700_000_000_000,
            bodyText = "Hello,\n\nPlease find the invoice attached.\n\nRegards",
            attachments = emptyList(),
        )

    @Test
    fun thePdfShowsHeadersAndThePlainTextBody() =
        runTest {
            val bytes = assertNotNull(PdfBoxMailPdfRenderer().render(message))
            Loader.loadPDF(bytes).use { document ->
                val text = PDFTextStripper().getText(document)
                assertTrue(text.contains("From: Sender <sender@example.com>"), text)
                assertTrue(text.contains("To: Me <me@example.com>"), text)
                assertTrue(text.contains("Subject: Invoice March"), text)
                assertTrue(text.contains("Date: "), text)
                assertTrue(text.contains("Please find the invoice attached."), text)
            }
        }

    @Test
    fun theSameMessageAlwaysMakesTheSameBytes() =
        runTest {
            val renderer = PdfBoxMailPdfRenderer()
            assertContentEquals(assertNotNull(renderer.render(message)), assertNotNull(renderer.render(message)))
        }
}
