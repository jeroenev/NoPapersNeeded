package dev.npn.core.index

import dev.npn.core.db.DocumentDao
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.isLive
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.fs.NpnPaths
import dev.npn.core.fs.readDocument
import dev.npn.core.pdf.PdfEngine
import dev.npn.core.util.NpnLog
import dev.npn.core.util.runCatchingCancellable
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Splits one library PDF into multiple new documents. PDFs are immutable, so the original is never
 * rewritten: each part is a brand-new file that flows through the normal [ImportService] path, and
 * so gets dedup, canonical inbox placement and an OCR queue entry for free. Trashing
 * the original and carrying metadata over is the session's job, not this service's.
 *
 * A split is all or nothing: when a later part cannot be made, or the split is cancelled, the parts
 * it already imported are taken back ([rollBack]) and the library is left as it was, but for a
 * document a part brought back, which stays.
 */
class SplitService(
    private val fs: LibraryFileSystem,
    private val pdfEngine: PdfEngine,
    private val importService: ImportService,
    private val documentDao: DocumentDao,
    private val log: NpnLog = NpnLog.None,
) {
    sealed interface Result {
        /** One [ImportResult] per requested segment, in segment order. */
        data class Done(
            val parts: List<ImportResult>,
        ) : Result

        data class Failed(
            val reason: SplitFailure,
        ) : Result
    }

    /**
     * Split [docId] into one new document per entry of [segments] (0-based page ranges). The
     * segments must be non-empty, contiguous and cover every page exactly once — the UI builds
     * them from "split after page N" cut points, so dropping or duplicating pages is a bug here,
     * not a feature. Parts are named `<original> (part N).pdf`.
     */
    suspend fun split(
        docId: String,
        segments: List<IntRange>,
    ): Result {
        val doc =
            documentDao.getByDocId(docId)?.takeIf { it.isLive }
                ?: return Result.Failed(SplitFailure.DOCUMENT_UNAVAILABLE)
        if (segments.size < 2) return Result.Failed(SplitFailure.INVALID_PARTS)
        return runCatchingCancellable { splitLoaded(doc, segments) }.getOrElse {
            log.warn("Reading ${doc.relPath} to split it failed", it)
            Result.Failed(SplitFailure.UNREADABLE_PDF)
        }
    }

    private suspend fun splitLoaded(
        doc: DocumentEntity,
        segments: List<IntRange>,
    ): Result {
        // The file at the document's path may be another one since the index last looked; its parts
        // would get this document's metadata, and the document would be trashed in its place.
        val bytes = fs.readDocument(doc.relPath, doc.docId) ?: return Result.Failed(SplitFailure.DOCUMENT_UNAVAILABLE)
        val pageCount = pdfEngine.open(bytes).use { it.pageCount }
        if (!coversEveryPage(segments, pageCount)) return Result.Failed(SplitFailure.INVALID_PARTS)

        val baseName = NpnPaths.stripPdfExtension(doc.fileName)
        // Next to the original, so a split never moves documents around.
        val folder = NpnPaths.parentDir(doc.relPath)
        val parts = ArrayList<ImportResult>(segments.size)
        var complete = false
        try {
            for ((i, segment) in segments.withIndex()) {
                val outcome =
                    runCatchingCancellable {
                        val partBytes = pdfEngine.extractPages(bytes, segment.toList())
                        importService.importBytes(partBytes, "$baseName (part ${i + 1}).pdf", folder)
                    }.getOrElse {
                        log.warn("Making part ${i + 1} of ${doc.relPath} failed", it)
                        return Result.Failed(SplitFailure.NOT_COMPLETED)
                    }
                parts.add(outcome)
            }
            complete = true
            return Result.Done(parts)
        } finally {
            @Suppress("SuspendFunInFinallySection") // rollBack runs in NonCancellable itself.
            if (!complete) rollBack(parts)
        }
    }

    /**
     * Take back the parts a split imported, for a split that will not complete. Only the parts it
     * created: a part that turned out to be an existing document ([ImportResult.Duplicate]), or
     * brought one back the library did not show ([ImportResult.Imported.broughtBack]), was there
     * before and stays. Runs even when the split was cancelled, and best effort: a part that
     * cannot be taken back is logged and stays in the library.
     */
    suspend fun rollBack(parts: List<ImportResult>) =
        withContext(NonCancellable) {
            for (part in parts.asReversed().filterIsInstance<ImportResult.Imported>()) {
                log.runLogged("Taking back split part ${part.relPath}") { importService.undoImport(part) }
            }
        }

    /** Whether [segments] are non-empty, contiguous from the first page, and end at the last. */
    private fun coversEveryPage(
        segments: List<IntRange>,
        pageCount: Int,
    ): Boolean {
        var expectedStart = 0
        for (segment in segments) {
            if (segment.isEmpty() || segment.first != expectedStart) return false
            expectedStart = segment.last + 1
        }
        return expectedStart == pageCount
    }
}
