package dev.npn.core.xmp

import android.content.Context
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDMetadata
import dev.npn.core.pdf.PdfBoxAndroidInit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Android [PdfXmpIO] backed by pdfbox-android (PDFBox 2.x API under
 * `com.tom_roush.pdfbox`).
 *
 * Like the desktop impl, this only moves raw XMP bytes and doc-info fields; XMP generation and
 * parsing stay in commonMain, sidestepping pdfbox-android's xmpbox gaps (PLAN section 2 / risks
 * table).
 *
 * pdfbox-android needs its resource loader before some operations (font/resource loading). The
 * constructor sets it up through the guard the PDF engine shares ([PdfBoxAndroidInit]). A failure
 * here never blocks baking, which does not touch fonts; the next user of the library tries again.
 */
class PdfBoxAndroidXmpIO(
    context: Context,
) : PdfXmpIO {
    init {
        try {
            PdfBoxAndroidInit.ensure(context)
        } catch (_: Throwable) {
            // Non-fatal: see the class comment.
        }
    }

    override suspend fun readXmp(pdfBytes: ByteArray): String? =
        withContext(Dispatchers.IO) {
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
        withContext(Dispatchers.IO) {
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
