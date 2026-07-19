package dev.npn.core.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Android [PdfEngine]. Text extraction and image->PDF use pdfbox-android (2.x API:
 * `PDDocument.load(bytes)`, not the 3.x `Loader`). Rendering uses the platform
 * `android.graphics.pdf.PdfRenderer` instead, because pdfbox-android's own renderer is
 * unreliable (PLAN.md / task note).
 *
 * App-wiring caveats:
 * - `PDFBoxResourceLoader.init(context)` must run once before any pdfbox-android use; done
 *   lazily here (guarded by a flag).
 * - `PdfRenderer` needs a seekable file, so bytes are written to a cache temp file that is
 *   deleted when the handle is closed.
 */
class PdfBoxAndroidEngine(private val context: Context) : PdfEngine {

    override suspend fun open(bytes: ByteArray): PdfDocumentHandle = withContext(Dispatchers.IO) {
        ensureInit(context)
        val pdDoc = try {
            PDDocument.load(bytes)
        } catch (t: Throwable) {
            throw PdfOpenException("Failed to load PDF", t)
        }
        // Temp file for the platform PdfRenderer.
        val temp = File.createTempFile("npn-pdf-", ".pdf", context.cacheDir)
        try {
            temp.writeBytes(bytes)
        } catch (t: Throwable) {
            pdDoc.close()
            temp.delete()
            throw PdfOpenException("Failed to stage PDF for rendering", t)
        }
        val pfd = try {
            ParcelFileDescriptor.open(temp, ParcelFileDescriptor.MODE_READ_ONLY)
        } catch (t: Throwable) {
            pdDoc.close()
            temp.delete()
            throw PdfOpenException("Failed to open PDF for rendering", t)
        }
        val renderer = try {
            PdfRenderer(pfd)
        } catch (t: Throwable) {
            pfd.close()
            pdDoc.close()
            temp.delete()
            throw PdfOpenException("Failed to create renderer", t)
        }
        AndroidPdfDocumentHandle(pdDoc, renderer, pfd, temp)
    }

    override suspend fun imagesToPdf(images: List<ByteArray>): ByteArray = withContext(Dispatchers.IO) {
        ensureInit(context)
        val doc = PDDocument()
        try {
            for (imageBytes in images) {
                val image: PDImageXObject = if (isJpeg(imageBytes)) {
                    JPEGFactory.createFromByteArray(doc, imageBytes)
                } else {
                    val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                        ?: error("Unsupported image bytes (not decodable)")
                    LosslessFactory.createFromImage(doc, bitmap)
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
        } finally {
            doc.close()
        }
    }

    override suspend fun extractPages(bytes: ByteArray, pageIndices: List<Int>): ByteArray =
        withContext(Dispatchers.IO) {
            ensureInit(context)
            require(pageIndices.isNotEmpty()) { "pageIndices must not be empty" }
            val source = try {
                PDDocument.load(bytes)
            } catch (t: Throwable) {
                throw PdfOpenException("Failed to load PDF", t)
            }
            try {
                require(pageIndices.all { it in 0 until source.numberOfPages }) {
                    "page index out of range (pages: ${source.numberOfPages})"
                }
                val dst = PDDocument()
                try {
                    for (index in pageIndices) dst.importPage(source.getPage(index))
                    val out = ByteArrayOutputStream()
                    dst.save(out)
                    out.toByteArray()
                } finally {
                    dst.close()
                }
            } finally {
                source.close()
            }
        }

    companion object {
        @Volatile
        private var initialized = false

        private fun ensureInit(context: Context) {
            if (initialized) return
            synchronized(this) {
                if (!initialized) {
                    PDFBoxResourceLoader.init(context.applicationContext)
                    initialized = true
                }
            }
        }
    }
}

private class AndroidPdfDocumentHandle(
    private val pdDoc: PDDocument,
    private val renderer: PdfRenderer,
    private val pfd: ParcelFileDescriptor,
    private val tempFile: File,
) : PdfDocumentHandle {

    override val pageCount: Int get() = pdDoc.numberOfPages

    override fun extractText(pageIndex: Int): String {
        val stripper = PDFTextStripper()
        stripper.startPage = pageIndex + 1
        stripper.endPage = pageIndex + 1
        return stripper.getText(pdDoc).trim()
    }

    override fun renderPagePng(pageIndex: Int, maxDimensionPx: Int): ByteArray {
        val page = renderer.openPage(pageIndex)
        try {
            val srcW = page.width.coerceAtLeast(1)
            val srcH = page.height.coerceAtLeast(1)
            val scale = pageRenderScale(srcW.toFloat(), srcH.toFloat(), maxDimensionPx)
            val outW = (srcW * scale).toInt().coerceAtLeast(1)
            val outH = (srcH * scale).toInt().coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
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
        try {
            pdDoc.close()
        } catch (_: Throwable) {
        }
        tempFile.delete()
    }
}
