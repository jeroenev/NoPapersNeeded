package dev.npn.core.xmp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.common.PDMetadata
import java.io.ByteArrayOutputStream

/**
 * Desktop [PdfXmpIO] backed by Apache PDFBox 3.x.
 *
 * Only moves raw XMP bytes and doc-info fields; all XMP generation/parsing lives in commonMain
 * ([XmpSerializer]/[XmpParser]), so xmpbox is not used here.
 */
class PdfBoxXmpIO : PdfXmpIO {

    override suspend fun readXmp(pdfBytes: ByteArray): String? = withContext(Dispatchers.IO) {
        Loader.loadPDF(pdfBytes).use { doc ->
            val meta = doc.documentCatalog?.metadata ?: return@withContext null
            meta.exportXMPMetadata().use { input ->
                val bytes = input.readBytes()
                if (bytes.isEmpty()) null else bytes.decodeToString()
            }
        }
    }

    override suspend fun writeBaked(
        pdfBytes: ByteArray,
        xmp: String,
        keywords: List<String>,
        title: String?,
    ): ByteArray = withContext(Dispatchers.IO) {
        // Loader.loadPDF throws IOException on invalid input, satisfying the "must throw" contract.
        Loader.loadPDF(pdfBytes).use { doc ->
            val xmpBytes = xmp.encodeToByteArray()
            val metadata = PDMetadata(doc)
            metadata.importXMPMetadata(xmpBytes)
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

    override suspend fun verify(pdfBytes: ByteArray): Boolean = withContext(Dispatchers.IO) {
        try {
            val xmp = readXmpInternal(pdfBytes) ?: return@withContext false
            val data = XmpParser.parse(xmp)
            data.documentId != null || data.tags.isNotEmpty()
        } catch (_: Throwable) {
            false
        }
    }

    private fun readXmpInternal(pdfBytes: ByteArray): String? {
        Loader.loadPDF(pdfBytes).use { doc: PDDocument ->
            val meta = doc.documentCatalog?.metadata ?: return null
            meta.exportXMPMetadata().use { input ->
                val bytes = input.readBytes()
                return if (bytes.isEmpty()) null else bytes.decodeToString()
            }
        }
    }
}
