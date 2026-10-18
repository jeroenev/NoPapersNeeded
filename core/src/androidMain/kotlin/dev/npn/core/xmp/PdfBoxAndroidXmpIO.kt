package dev.npn.core.xmp

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import dev.npn.core.pdf.PdfBoxAndroidInit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Android [PdfXmpIO] backed by pdfbox-android (PDFBox 2.x API under
 * `com.tom_roush.pdfbox`).
 *
 * Like the desktop impl, this only moves raw XMP bytes and doc-info fields; XMP generation and
 * parsing stay in commonMain, sidestepping pdfbox-android's xmpbox gaps.
 *
 * pdfbox-android needs its resource loader before some operations (font/resource loading). Each
 * call sets it up first, through the guard the PDF engine shares ([PdfBoxAndroidInit]), and fails
 * as the call when that fails; the next call tries again.
 */
internal class PdfBoxAndroidXmpIO(
    private val context: Context,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PdfXmpIO {
    override suspend fun readXmp(pdfBytes: ByteArray): String? =
        withContext(io) {
            PdfBoxAndroidInit.ensure(context)
            PDDocument.load(pdfBytes).use { doc ->
                val meta = doc.documentCatalog?.metadata ?: return@withContext null
                meta.exportXMPMetadata().use { input ->
                    val bytes = input.readBytes()
                    if (bytes.isEmpty()) null else decodeXmpBytes(bytes)
                }
            }
        }

    override suspend fun writeBaked(
        pdfBytes: ByteArray,
        xmp: String,
        keywords: List<String>,
        title: String?,
    ): ByteArray =
        withContext(io) {
            PdfBoxAndroidInit.ensure(context)
            // PDDocument.load throws IOException on invalid input, satisfying the "must throw" contract.
            PDDocument.load(pdfBytes).use { doc ->
                val xmpBytes = xmp.encodeToByteArray()
                val metadata = PDMetadata(doc, ByteArrayInputStream(xmpBytes))
                doc.documentCatalog.metadata = metadata

                val info = doc.documentInformation
                if (keywords.isNotEmpty()) {
                    info.keywords = keywords.joinToString(", ")
                }
                if (title != null) {
                    info.title = title
                }

                val out = ByteArrayOutputStream()
                doc.save(out)
                out.toByteArray()
            }
        }
}
