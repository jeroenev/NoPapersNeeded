package dev.npn.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.core.graphics.createBitmap
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Android [PdfEngine]. Text extraction and image->PDF use pdfbox-android (2.x API:
 * `PDDocument.load(bytes)`, not the 3.x `Loader`). Rendering uses the platform
 * `android.graphics.pdf.PdfRenderer` instead because pdfbox-android's renderer is unreliable.
 *
 * A handle opens each backend only when a call needs it ([LazyPdfDocumentHandle]): drawing a page
 * never parses the document with pdfbox-android, and counting pages or reading text never writes
 * the copy `PdfRenderer` needs (it wants a seekable file, so the bytes are staged in the cache
 * directory and deleted when the handle closes). pdfbox-android's resource loader is set up once,
 * through [PdfBoxAndroidInit].
 */
class PdfBoxAndroidEngine(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PdfEngine {
    override suspend fun open(bytes: ByteArray): PdfDocumentHandle =
        LazyPdfDocumentHandle(openText = { openText(bytes) }, openPages = { openPages(bytes) })

    private fun openText(bytes: ByteArray): PdfTextBackend = PdfBoxTextBackend(loadOrThrow(bytes))

    private fun loadOrThrow(bytes: ByteArray): PDDocument {
        PdfBoxAndroidInit.ensure(context)
        return try {
            PDDocument.load(bytes)
        } catch (t: Throwable) {
            throw PdfOpenException("Failed to load PDF", t)
        }
    }

    /** Stage [bytes] in the cache directory, where `PdfRenderer` can seek, and open the renderer. */
    private fun openPages(bytes: ByteArray): PdfPageBackend {
        val temp = File.createTempFile("npn-pdf-", ".pdf", context.cacheDir)
        var pfd: ParcelFileDescriptor? = null
        try {
            temp.writeBytes(bytes)
            val descriptor = ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY).also { pfd = it }
            return RendererPageBackend(PdfRenderer(descriptor), descriptor, temp)
        } catch (t: Throwable) {
            try {
                pfd?.close()
            } catch (_: Throwable) {
            }
            temp.delete()
            throw PdfOpenException("Failed to stage or open PDF for rendering", t)
        }
    }

    override suspend fun imagesToPdf(images: List<ByteArray>): ByteArray =
        withContext(io) {
            PdfBoxAndroidInit.ensure(context)
            PDDocument().use { doc ->
                for (imageBytes in images) {
                    val image: PDImageXObject =
                        if (isJpeg(imageBytes)) {
                            JPEGFactory.createFromByteArray(doc, imageBytes)
                        } else {
                            val bitmap =
                                BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                                    ?: error("Unsupported image bytes (not decodable)")
                            LosslessFactory.createFromImage(doc, bitmap)
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
            loadOrThrow(bytes).use { source ->
                require(pageIndices.all { it in 0 until source.numberOfPages }) {
                    "page index out of range (pages: ${source.numberOfPages})"
                }
                PDDocument().use { dst ->
                    for (index in pageIndices) dst.importPage(source.getPage(index))
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

private class PdfBoxTextBackend(
    private val document: PDDocument,
) : PdfTextBackend {
    override val pageCount: Int get() = document.numberOfPages

    override fun extractText(pageIndex: Int): String {
        val stripper = PDFTextStripper()
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        return stripper.getText(document).trim()
    }

    override fun close() {
        try {
            document.close()
        } catch (_: Throwable) {
        }
    }
}

private class RendererPageBackend(
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
    private val tempFile: File,
) : PdfPageBackend {
    override val pageCount: Int get() = renderer.pageCount

    override fun renderPagePng(
        pageIndex: Int,
        maxDimensionPx: Int,
    ): ByteArray {
        val page = renderer.openPage(pageIndex)
        try {
            val srcW = page.width.coerceAtLeast(1)
            val srcH = page.height.coerceAtLeast(1)
            val scale = pageRenderScale(srcW.toFloat(), srcH.toFloat(), maxDimensionPx)
            val outW = (srcW * scale).toInt().coerceAtLeast(1)
            val outH = (srcH * scale).toInt().coerceAtLeast(1)
            val bitmap = createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            // White background: PDF pages are drawn on transparent by default.
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            bitmap.recycle()
            return out.toByteArray()
        } finally {
            page.close()
        }
    }

    override fun close() {
        try {
            renderer.close()
        } catch (_: Throwable) {
        }
        try {
            pfd.close()
        } catch (_: Throwable) {
        }
        tempFile.delete()
    }
}
