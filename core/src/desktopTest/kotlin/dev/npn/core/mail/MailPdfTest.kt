package dev.npn.core.mail

import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
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
            val renderer = PdfBoxMailPdfRenderer(zone = { ZoneId.of("Europe/Brussels") })
            assertContentEquals(assertNotNull(renderer.render(message)), assertNotNull(renderer.render(message)))
        }

    /** The date is this device's local time, with its offset, not a UTC time that reads as local. */
    @Test
    fun theDateIsInTheLocalZoneWithItsOffset() =
        runTest {
            val brussels = PdfBoxMailPdfRenderer(zone = { ZoneId.of("Europe/Brussels") })
            val honolulu = PdfBoxMailPdfRenderer(zone = { ZoneId.of("Pacific/Honolulu") })
            // 1_700_000_000_000 is 2023-11-14 22:13 UTC.
            val inBrussels = text(assertNotNull(brussels.render(message)))
            val inHonolulu = text(assertNotNull(honolulu.render(message)))
            assertTrue(inBrussels.contains("Date: 2023-11-14 23:13 UTC+01:00"), inBrussels)
            assertTrue(inHonolulu.contains("Date: 2023-11-14 12:13 UTC-10:00"), inHonolulu)
        }

    /** No font draws a tab or a control character: they printed as `?`. */
    @Test
    fun tabsAndControlCharactersDoNotPrintAsQuestionMarks() =
        runTest {
            val tabbed =
                message.copy(
                    subject = "Invoice\tMarch",
                    bodyText = "Item\tPrice\nPaper\t3\u000C\u200B\uFEFF euro",
                )
            val text = text(assertNotNull(PdfBoxMailPdfRenderer().render(tabbed)))
            assertFalse(text.contains('?'), text)
            assertTrue(text.contains("Item"), text)
            assertTrue(text.contains("euro"), text)
        }

    private fun text(bytes: ByteArray): String = Loader.loadPDF(bytes).use { PDFTextStripper().getText(it) }

    @Test
    fun textBeyondLatinIsKeptAndWhatTheFontLacksIsMarked() =
        runTest {
            val greek = message.copy(subject = "Τιμολόγιο", bodyText = "Счёт № 42 · Zürich 12,50 € · 請求書")
            val bytes = assertNotNull(PdfBoxMailPdfRenderer().render(greek))
            Loader.loadPDF(bytes).use { document ->
                val text = PDFTextStripper().getText(document)
                assertTrue(text.contains("Subject: Τιμολόγιο"), text)
                assertTrue(text.contains("Счёт № 42 · Zürich 12,50 €"), text)
                assertTrue(text.contains("???"), text)
            }
        }

    /** A body that is one long unbroken token (an encoded blob in a text part) is cut into lines at once. */
    @Test
    fun aVeryLongWordIsWrappedInLinearTime() =
        runTest {
            val blob = "A".repeat(400_000)
            val started = System.nanoTime()
            val bytes = assertNotNull(PdfBoxMailPdfRenderer().render(message.copy(bodyText = blob)))
            val seconds = (System.nanoTime() - started) / 1e9
            assertTrue(seconds < 20, "took $seconds s")
            Loader.loadPDF(bytes).use { document -> assertTrue(document.numberOfPages > 10) }
        }
}
