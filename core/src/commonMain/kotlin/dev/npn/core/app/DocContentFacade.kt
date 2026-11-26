package dev.npn.core.app

// ---------- documents as bytes / viewer / export ----------

/**
 * Documents as bytes: thumbnails, pages, export copies and the in-place bake.
 *
 * The nullable reads return null whenever there is nothing to show — no such document, bytes that
 * cannot be read, a closed session — and log the cause; they never throw but for cancellation, so
 * a caller needs no wrapper around them.
 */
interface DocContentFacade {
    suspend fun thumbnailPath(docId: String): String? // ensure + return cache path, null on failure

    suspend fun pageCount(docId: String): Int?

    suspend fun renderPage(docId: String, pageIndex: Int, maxDim: Int): ByteArray? // png

    /**
     * Stream the document's library bytes, exactly as stored (never baked), into [sink] chunk by
     * chunk as the file is read, never holding it whole: what a print job sends. True when they were
     * the document's own; false when there is no such document, its file could not be read, or the
     * file now holds other bytes (written over since the index looked), and what [sink] got must
     * then be thrown away. A [sink] that throws stops the read; its exception propagates.
     */
    suspend fun streamDocument(
        docId: String,
        sink: (ByteArray, Int) -> Unit,
    ): Boolean

    suspend fun bakedCopyBytes(docId: String): ByteArray? // export/share with XMP baked

    suspend fun bakeInPlace(docId: String): BakeOutcome // explicit user action; emits relink

    suspend fun clearThumbnailCache(): Int // drop cached thumbnails; returns count cleared

    suspend fun extractedText(docId: String): String? // OCR/embedded full text, null if none yet
}

/** What [DocContentFacade.bakeInPlace] did. */
sealed interface BakeOutcome {
    /** The library file now carries the metadata; its new hash was relinked. */
    data object Baked : BakeOutcome

    data class Failed(
        val reason: BakeFailure,
    ) : BakeOutcome
}

/** Why a bake did not happen. The underlying cause, if any, is in the session log. */
enum class BakeFailure {
    /** The document is gone, trashed or missing, or the file at its path is no longer its own. */
    DOCUMENT_UNAVAILABLE,

    /** The PDF cannot carry the metadata (unreadable, or the rewrite did not verify); it is untouched. */
    UNSUPPORTED_PDF,

    /** Writing the rewritten PDF or recording its new hash failed. */
    NOT_SAVED,
}
