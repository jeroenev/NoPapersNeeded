package dev.npn.core.pdf

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import java.awt.Color
import kotlin.test.Test

class PdfBoxEngineTest {
    private val engine = PdfBoxEngine()

    @Test
    fun readsNestedUnicodeAttachmentsAndRejectsOversizedDecodedStreams() =
        runTest {
            val xml = "<Invoice/>".encodeToByteArray()
            val pdf = PdfFixtures.pdfWithEmbeddedFile("invoice.xml", xml, nested = true, unicodeOnly = true)
            engine.readAll(pdf).single().second shouldBe xml.size
            val large = ByteArray(MAX_EMBEDDED_XML_BYTES + 1) { 65 }
            engine.readAll(PdfFixtures.pdfWithEmbeddedFile("invoice.xml", large)) shouldBe emptyList()
        }

    @Test
    fun manyXmlAttachmentsAreReadOneAtATimeUpToACount() =
        runTest {
            val pdf = PdfFixtures.pdfWithEmbeddedFiles(List(60) { PdfFixtures.Attachment("junk$it.xml", junk(1024)) })
            engine.readAll(pdf) shouldHaveSize MAX_EMBEDDED_XML_FILES
        }

    @Test
    fun compressibleAttachmentsStopAtTheTotalBudget() =
        runTest {
            // A small PDF whose attachments inflate to far more than the budget: each one alone is
            // under the per-file cap, so only the total holds them back.
            val each = 6 * 1024 * 1024
            val pdf = PdfFixtures.pdfWithEmbeddedFiles(List(6) { PdfFixtures.Attachment("junk$it.xml", junk(each)) })
            pdf.size shouldBeLessThan 1024 * 1024
            val read = engine.readAll(pdf)
            read.sumOf { it.second } shouldBeLessThanOrEqual MAX_EMBEDDED_XML_TOTAL_BYTES
            read shouldHaveSize MAX_EMBEDDED_XML_TOTAL_BYTES / each
        }

    @Test
    fun aKnownInvoiceNameIsReadFirstAndEndsTheSearch() =
        runTest {
            val attachments =
                List(20) { PdfFixtures.Attachment("junk$it.xml", junk(64)) } +
                    PdfFixtures.Attachment("Factur-X.xml", "<invoice/>".encodeToByteArray())
            val seen = mutableListOf<String>()
            val found =
                engine.firstEmbeddedXml(PdfFixtures.pdfWithEmbeddedFiles(attachments)) { file ->
                    seen += file.name
                    file.name.takeIf { it == "Factur-X.xml" }
                }
            found shouldBe "Factur-X.xml"
            seen shouldBe listOf("Factur-X.xml")
        }

    @Test
    fun anAssociatedFileRelationshipIsReadBeforeOtherNames() =
        runTest {
            val attachments =
                List(20) { PdfFixtures.Attachment("junk$it.xml", junk(64)) } +
                    PdfFixtures.Attachment(
                        "rechnung.xml",
                        "<invoice/>".encodeToByteArray(),
                        relationship = "Alternative",
                    )
            engine.readAll(PdfFixtures.pdfWithEmbeddedFiles(attachments)).first().first shouldBe "rechnung.xml"
        }

    /** Every attachment [PdfEngine.firstEmbeddedXml] hands over, by name and size, in order. */
    private suspend fun PdfEngine.readAll(pdf: ByteArray): List<Pair<String, Int>> {
        val seen = mutableListOf<Pair<String, Int>>()
        firstEmbeddedXml<Unit>(pdf) { file ->
            seen += file.name to file.bytes.size
            null
        }
        return seen
    }

    private fun junk(size: Int): ByteArray = ByteArray(size) { 'a'.code.toByte() }

    @Test
    fun pageCountReflectsDocument() =
        runTest {
            engine.open(PdfFixtures.embeddedTextPdf("invoice 42 acme corporation")).use { handle ->
                handle.pageCount shouldBe 2
            }
        }

    @Test
    fun extractTextPerPageReadsEmbeddedLayer() =
        runTest {
            engine.open(PdfFixtures.embeddedTextPdf("invoice 42 acme corporation")).use { handle ->
                handle.extractText(0) shouldContain "invoice 42 acme corporation"
                handle.extractText(1) shouldContain "invoice 42 acme corporation"
            }
        }

    @Test
    fun extractTextEmptyForImageOnlyPage() =
        runTest {
            engine.open(PdfFixtures.imageOnlyPdf()).use { handle ->
                handle.extractText(0) shouldBe ""
            }
        }

    @Test
    fun renderPagePngReturnsDecodablePng() =
        runTest {
            engine.open(PdfFixtures.embeddedTextPdf("hello")).use { handle ->
                val png = handle.renderPagePng(0, 480)
                png.size shouldBeGreaterThan 0
                isPng(png).shouldBeTrue()
            }
        }

    @Test
    fun imagesToPdfRoundtripsTwoPages() =
        runTest {
            val pdf = engine.imagesToPdf(listOf(PdfFixtures.pngBytes(), PdfFixtures.jpegBytes()))
            looksLikePdf(pdf).shouldBeTrue()
            Loader.loadPDF(pdf).use { it.numberOfPages shouldBe 2 }
        }

    @Test
    fun theSameImagesMakeTheSamePdfAtAnyTime() =
        runTest {
            val images = listOf(PdfFixtures.pngBytes(), PdfFixtures.jpegBytes())
            val first = engine.imagesToPdf(images)
            waitForTheClockToTick()
            val again = engine.imagesToPdf(images)
            again shouldBe first
            documentId(first) shouldHaveSize 2
        }

    @Test
    fun otherImagesMakeAnotherPdfWithAnotherId() =
        runTest {
            val png = PdfFixtures.pngBytes()
            val jpeg = PdfFixtures.jpegBytes()
            val pdf = engine.imagesToPdf(listOf(png, jpeg))
            for (other in listOf(listOf(jpeg, png), listOf(PdfFixtures.pngBytes(color = Color.GREEN), jpeg))) {
                val otherPdf = engine.imagesToPdf(other)
                otherPdf shouldNotBe pdf
                documentId(otherPdf) shouldNotBe documentId(pdf)
            }
        }

    @Test
    fun openThrowsOnInvalidPdf() =
        runTest {
            try {
                engine.open("garbage".encodeToByteArray())
                error("expected PdfOpenException")
            } catch (_: PdfOpenException) {
                // expected
            }
        }

    /** The trailer's /ID strings of [pdf], in hex. */
    private fun documentId(pdf: ByteArray): List<String> =
        Loader.loadPDF(pdf).use { doc ->
            doc.document.trailer
                .getCOSArray(COSName.ID)
                .map { (it as COSString).toHexString() }
        }

    /** Returns once the clock PDFBox seeds a missing /ID from has moved on. */
    private fun waitForTheClockToTick() {
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() == start) Thread.onSpinWait()
    }
}
