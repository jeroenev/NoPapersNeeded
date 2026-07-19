package dev.npn.core.index

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.pdf.PdfEngine

/**
 * Splits one library PDF into multiple new documents (PLAN "PDFs are immutable": the original is
 * never rewritten; each part is a brand-new file that flows through the normal [ImportService]
 * path, so parts get dedup, canonical inbox placement and an OCR queue entry for free). Trashing
 * the original and carrying metadata over is the session's job, not this service's.
 */
class SplitService(
    private val fs: LibraryFileSystem,
    private val pdfEngine: PdfEngine,
    private val importService: ImportService,
    private val documentDao: DocumentDao,
) {
    sealed interface Result {
        /** One [ImportResult] per requested segment, in segment order. */
        data class Done(val parts: List<ImportResult>) : Result
        data class Failed(val message: String) : Result
    }

    /**
     * Split [docId] into one new document per entry of [segments] (0-based page ranges). The
     * segments must be non-empty, contiguous and cover every page exactly once — the UI builds
     * them from "split after page N" cut points, so dropping or duplicating pages is a bug here,
     * not a feature. Parts are named `<original> (part N).pdf`.
     */
    suspend fun split(docId: String, segments: List<IntRange>): Result {
        val doc = documentDao.getByDocId(docId)
        val precondition = when {
            doc == null -> "Document not found"
            doc.trashed || doc.deleted || doc.missing -> "Document is not available"
            segments.size < 2 -> "A split needs at least two parts"
            else -> null
        }
        if (doc == null || precondition != null) {
            return Result.Failed(precondition ?: "Document not found")
        }
        return try {
            splitLoaded(doc, segments)
        } catch (t: Throwable) {
            Result.Failed("Could not read the PDF: ${t.message}")
        }
    }

    private suspend fun splitLoaded(doc: DocumentEntity, segments: List<IntRange>): Result {
        val bytes = fs.readBytes(doc.relPath)
        val handle = pdfEngine.open(bytes)
        val pageCount = try {
            handle.pageCount
        } finally {
            handle.close()
        }
        validateSegments(segments, pageCount)?.let { return Result.Failed(it) }

        val baseName = doc.fileName.removeSuffix(".pdf").removeSuffix(".PDF")
        val parts = ArrayList<ImportResult>(segments.size)
        for ((i, segment) in segments.withIndex()) {
            val outcome = try {
                val partBytes = pdfEngine.extractPages(bytes, segment.toList())
                importService.importBytes(partBytes, "$baseName (part ${i + 1}).pdf", toDir = parentDirOf(doc.relPath))
            } catch (t: Throwable) {
                return Result.Failed("Splitting failed on part ${i + 1}: ${t.message}")
            }
            parts.add(outcome)
        }
        return Result.Done(parts)
    }

    /** Same folder as the original ("" = library root) so a split never moves documents around. */
    private fun parentDirOf(relPath: String): String =
        relPath.substringBeforeLast('/', missingDelimiterValue = "")

    /** Null when valid, else a user-facing error message. */
    private fun validateSegments(segments: List<IntRange>, pageCount: Int): String? {
        var expectedStart = 0
        for (segment in segments) {
            if (segment.isEmpty()) return "Empty page range"
            if (segment.first != expectedStart) return "Page ranges must be contiguous from page 1"
            expectedStart = segment.last + 1
        }
        if (expectedStart != pageCount) return "Page ranges must cover all $pageCount pages"
        return null
    }
}
