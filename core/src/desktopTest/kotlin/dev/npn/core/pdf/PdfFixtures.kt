package dev.npn.core.pdf

import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDEmbeddedFilesNameTreeNode
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.filespecification.PDComplexFileSpecification
import org.apache.pdfbox.pdmodel.common.filespecification.PDEmbeddedFile
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/** Test fixtures: real PDFs and images generated with PDFBox 3.x in-process. */
object PdfFixtures {
    /** One-page PDF carrying [bytes] as an embedded attachment named [name]. */
    fun pdfWithEmbeddedFile(
        name: String,
        bytes: ByteArray,
        nested: Boolean = false,
        unicodeOnly: Boolean = false,
    ): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            val embedded =
                PDEmbeddedFile(doc).apply {
                    subtype = "text/xml"
                    cosObject.createOutputStream(COSName.FLATE_DECODE).use { it.write(bytes) }
                }
            val spec =
                PDComplexFileSpecification().apply {
                    file = name
                    if (unicodeOnly) embeddedFileUnicode = embedded else embeddedFile = embedded
                }
            val leaf = PDEmbeddedFilesNameTreeNode().apply { names = mapOf(name to spec) }
            val tree = if (nested) PDEmbeddedFilesNameTreeNode().apply { kids = listOf(leaf) } else leaf
            doc.documentCatalog.names =
                PDDocumentNameDictionary(doc.documentCatalog).apply { embeddedFiles = tree }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }

    /** One attachment of [pdfWithEmbeddedFiles]: its name, bytes and PDF/A-3 `AFRelationship`, if any. */
    class Attachment(
        val name: String,
        val bytes: ByteArray,
        val relationship: String? = null,
    )

    /** One-page PDF carrying every one of [attachments], in this order, in one flat name tree. */
    fun pdfWithEmbeddedFiles(attachments: List<Attachment>): ByteArray =
        PDDocument().use { doc ->
            doc.addPage(PDPage(PDRectangle.A4))
            // Name-tree keys must be unique and are kept in sorted order: number them so the listing
            // order is the order given.
            val names =
                attachments.withIndex().associate { (index, attachment) ->
                    val embedded =
                        PDEmbeddedFile(doc).apply {
                            subtype = "text/xml"
                            cosObject.createOutputStream(COSName.FLATE_DECODE).use { it.write(attachment.bytes) }
                        }
                    val spec =
                        PDComplexFileSpecification().apply {
                            file = attachment.name
                            embeddedFile = embedded
                            attachment.relationship?.let { cosObject.setName("AFRelationship", it) }
                        }
                    index.toString().padStart(4, '0') to spec
                }
            doc.documentCatalog.names =
                PDDocumentNameDictionary(doc.documentCatalog).apply {
                    embeddedFiles = PDEmbeddedFilesNameTreeNode().apply { this.names = names }
                }
            val out = ByteArrayOutputStream()
            doc.save(out)
            out.toByteArray()
        }

    /** Two-page PDF with an embedded text layer containing [lines] on each page. */
    fun embeddedTextPdf(vararg lines: String): ByteArray {
        PDDocument().use { doc ->
            repeat(2) { pageNo ->
                val page = PDPage(PDRectangle.A4)
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.beginText()
                    cs.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
                    cs.newLineAtOffset(72f, 700f)
                    val text =
                        if (lines.isNotEmpty()) {
                            lines.joinToString(" ") + " page ${pageNo + 1} of the document"
                        } else {
                            "page ${pageNo + 1}"
                        }
                    cs.showText(text)
                    cs.endText()
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    /** PDF of [pages] pages whose only content is a rendered image (no text layer). */
    fun imageOnlyPdf(pages: Int = 1): ByteArray {
        PDDocument().use { doc ->
            val img = solidImage(400, 300, Color.LIGHT_GRAY)
            val xobj = LosslessFactory.createFromImage(doc, img)
            repeat(pages) {
                val page = PDPage(PDRectangle(img.width.toFloat(), img.height.toFloat()))
                doc.addPage(page)
                PDPageContentStream(doc, page).use { cs ->
                    cs.drawImage(xobj, 0f, 0f, img.width.toFloat(), img.height.toFloat())
                }
            }
            val out = ByteArrayOutputStream()
            doc.save(out)
            return out.toByteArray()
        }
    }

    fun pngBytes(
        width: Int = 100,
        height: Int = 80,
        color: Color = Color.RED,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(solidImage(width, height, color), "png", out)
        return out.toByteArray()
    }

    fun jpegBytes(
        width: Int = 120,
        height: Int = 90,
        color: Color = Color.BLUE,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        // JPEG has no alpha channel; use TYPE_INT_RGB.
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        ImageIO.write(img, "jpg", out)
        return out.toByteArray()
    }

    private fun solidImage(
        width: Int,
        height: Int,
        color: Color,
    ): BufferedImage {
        val img = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = color
        g.fillRect(0, 0, width, height)
        g.dispose()
        return img
    }
}

/** The eight-byte PNG signature: 89 50 4E 47 0D 0A 1A 0A. */
private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** Whether [bytes] start with the PNG signature. */
internal fun isPng(bytes: ByteArray): Boolean =
    bytes.size >= PNG_SIGNATURE.size && bytes.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE)

/** Whether [bytes] start with the PDF header `%PDF`. */
internal fun looksLikePdf(bytes: ByteArray): Boolean = bytes.size >= 4 && bytes.copyOf(4).decodeToString() == "%PDF"
