package dev.npn.core.pdf

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import org.apache.pdfbox.Loader
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
    fun openThrowsOnInvalidPdf() =
        runTest {
            try {
                engine.open("garbage".encodeToByteArray())
                error("expected PdfOpenException")
            } catch (_: PdfOpenException) {
                // expected
            }
        }
}
