package dev.npn.core.pdf

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDNameTreeNode
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.InflaterInputStream
import javax.imageio.ImageIO

/**
 * Desktop [PdfEngine] using Apache PDFBox 3.x. Notable 3.x specifics vs pdfbox-android 2.x:
 * `Loader.loadPDF(bytes)` (not `PDDocument.load`), and `PDFRenderer.renderImage` returns a
 * `BufferedImage`.
 */
internal class PdfBoxEngine(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PdfEngine {
    override suspend fun open(bytes: ByteArray): PdfDocumentHandle =
        withContext(io) { PdfBoxDocumentHandle(loadOrThrow(bytes)) }

    override suspend fun embeddedXmlFiles(bytes: ByteArray): List<EmbeddedXmlFile> =
        withContext(io) {
            loadOrThrow(bytes).use { doc ->
                val tree = doc.documentCatalog.names?.embeddedFiles ?: return@use emptyList()
                val result = mutableListOf<EmbeddedXmlFile>()
                val seen = mutableSetOf<org.apache.pdfbox.cos.COSDictionary>()

                fun visit(
                    node: PDNameTreeNode<PDComplexFileSpecification>,
                    depth: Int,
                ) {
                    if (depth > MAX_NAME_TREE_DEPTH || !seen.add(node.cosObject)) return
                    node.names.orEmpty().forEach { (key, spec) ->
                        val name = spec.filename ?: key
                        if (name.lowercase().endsWith(".xml")) {
                            val file = spec.embeddedFileUnicode ?: spec.embeddedFile
                            file?.readSafeXml()?.let { result.add(EmbeddedXmlFile(name, it)) }
                        }
                    }
                    node.kids.orEmpty().forEach { visit(it, depth + 1) }
                }
                visit(tree, 0)
                result
            }
        }

    private fun loadOrThrow(bytes: ByteArray): PDDocument =
        try {
            Loader.loadPDF(bytes)
        } catch (e: Exception) {
            throw PdfOpenException("Failed to load PDF", e)
        }

    override suspend fun imagesToPdf(images: List<ByteArray>): ByteArray =
        withContext(io) {
            PDDocument().use { doc ->
                for (imageBytes in images) {
                    val image: PDImageXObject =
                        when {
                            isJpeg(imageBytes) -> {
                                JPEGFactory.createFromByteArray(doc, imageBytes)
                            }

                            else -> {
                                val bufferedImage: BufferedImage =
                                    ImageIO.read(ByteArrayInputStream(imageBytes))
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
                doc.identifyBy(images)
                val out = ByteArrayOutputStream()
                doc.save(out)
                out.toByteArray()
            }
        }

    override suspend fun extractPages(
        bytes: ByteArray,
        pageIndices: List<Int>,
    ): ByteArray =
        withContext(io) {
            require(pageIndices.isNotEmpty()) { "pageIndices must not be empty" }
            loadOrThrow(bytes).use { src ->
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

/** Gives the document the `/ID` of [images] ([imagesPdfId]) rather than one PDFBox seeds from the clock. */
private fun PDDocument.identifyBy(images: List<ByteArray>) {
    val id = COSString(imagesPdfId(images))
    val ids =
        COSArray().apply {
            add(id)
            add(id)
        }
    document.trailer.setItem(COSName.ID, ids)
}

private class PdfBoxDocumentHandle(
    private val doc: PDDocument,
) : PdfDocumentHandle {
    override val pageCount: Int get() = doc.numberOfPages

    override val creationDate: Long? get() = doc.documentInformation.creationDate?.timeInMillis

    private val renderer: PDFRenderer by lazy { PDFRenderer(doc) }

    override fun extractText(pageIndex: Int): String {
        val stripper = PDFTextStripper()
        // PDFTextStripper page numbers are 1-based and inclusive.
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        return stripper.getText(doc).trim()
    }

    override fun renderPagePng(
        pageIndex: Int,
        maxDimensionPx: Int,
    ): ByteArray {
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

private const val MAX_NAME_TREE_DEPTH = 32

/** Decode only unfiltered or Flate streams, incrementally, without PDFBox's eager filter buffers. */
private fun PDEmbeddedFile.readSafeXml(): ByteArray? {
    val stream = cosObject
    if (stream.getDictionaryObject(COSName.DECODE_PARMS) != null) return null
    val filters = stream.filters
    val filter = if (filters is COSArray && filters.size() == 1) filters.getObject(0) else filters
    if (filter != null && filter != COSName.FLATE_DECODE && filter != COSName.FL) return null
    return stream.createRawInputStream().use { raw ->
        if (filter == null) {
            raw.readCapped(MAX_EMBEDDED_XML_BYTES)
        } else {
            InflaterInputStream(raw).use { it.readCapped(MAX_EMBEDDED_XML_BYTES) }
        }
    }
}
