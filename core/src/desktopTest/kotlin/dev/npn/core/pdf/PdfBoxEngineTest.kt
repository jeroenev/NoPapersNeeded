package dev.npn.core.pdf

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
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
