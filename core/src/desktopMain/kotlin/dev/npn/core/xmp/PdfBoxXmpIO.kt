package dev.npn.core.xmp

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.common.PDMetadata
import java.io.ByteArrayOutputStream

/**
 * Desktop [PdfXmpIO] backed by Apache PDFBox 3.x.
 *
 * Only moves raw XMP bytes and doc-info fields; all XMP generation/parsing lives in commonMain
 * ([XmpSerializer]/[XmpParser]), so xmpbox is not used here.
 */
internal class PdfBoxXmpIO(
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : PdfXmpIO {
    override suspend fun readXmp(pdfBytes: ByteArray): String? =
        withContext(io) {
            Loader.loadPDF(pdfBytes).use { doc ->
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
}
