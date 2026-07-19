package dev.npn.core.pdf

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Desktop [PdfEngine] using Apache PDFBox 3.x. Notable 3.x specifics vs pdfbox-android 2.x:
 * `Loader.loadPDF(bytes)` (not `PDDocument.load`), and `PDFRenderer.renderImage` returns a
 * `BufferedImage`.
 */
class PdfBoxEngine : PdfEngine {

    override suspend fun open(bytes: ByteArray): PdfDocumentHandle = withContext(Dispatchers.IO) {
        val doc = try {
            Loader.loadPDF(bytes)
        } catch (t: Throwable) {
            throw PdfOpenException("Failed to load PDF", t)
        }
        PdfBoxDocumentHandle(doc)
    }

    override suspend fun imagesToPdf(images: List<ByteArray>): ByteArray = withContext(Dispatchers.IO) {
        PDDocument().use { doc ->
            for (imageBytes in images) {
                val image: PDImageXObject = when {
                    isJpeg(imageBytes) -> JPEGFactory.createFromByteArray(doc, imageBytes)
                    else -> {
                        val bufferedImage: BufferedImage = ImageIO.read(ByteArrayInputStream(imageBytes))
                            ?: error("Unsupported image bytes (not decodable)")
                        LosslessFactory.createFromImage(doc, bufferedImage)
                    }
                }
                val page = PDPage(PDRectangle(image.width.toFloat(), image.height.toFloat()))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(image, 0f, 0f, image.width.toFloat(), image.height.toFloat())
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }
    }

    override suspend fun extractPages(bytes: ByteArray, pageIndices: List<Int>): ByteArray =
        withContext(Dispatchers.IO) {
            require(pageIndices.isNotEmpty()) { "pageIndices must not be empty" }
            val source = try {
                Loader.loadPDF(bytes)
            } catch (t: Throwable) {
                throw PdfOpenException("Failed to load PDF", t)
            }
            source.use { src ->
                require(pageIndices.all { it in 0 until src.numberOfPages }) {
                    "page index out of range (pages: ${src.numberOfPages})"
                }
                PDDocument().use { dst ->
                    for (index in pageIndices) dst.importPage(src.getPage(index))
                    val out = ByteArrayOutputStream()
                    dst.save(out)
                    out.toByteArray()
                }
            }
        }
}

private class PdfBoxDocumentHandle(private val doc: PDDocument) : PdfDocumentHandle {

    override val pageCount: Int get() = doc.numberOfPages

    private val renderer: PDFRenderer by lazy { PDFRenderer(doc) }

    override fun extractText(pageIndex: Int): String {
        val stripper = PDFTextStripper()
        // PDFTextStripper page numbers are 1-based and inclusive.
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        return stripper.getText(doc).trim()
    }

    override fun renderPagePng(pageIndex: Int, maxDimensionPx: Int): ByteArray {
        val page = doc.getPage(pageIndex)
        val box = page.cropBox
        val scale = pageRenderScale(box.width, box.height, maxDimensionPx)
        val image = renderer.renderImage(pageIndex, scale)
        val out = ByteArrayOutputStream()
        ImageIO.write(image, "png", out)
        return out.toByteArray()
    }

    override fun close() {
        doc.close()
    }
}
