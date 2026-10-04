package dev.npn.core.app

// ---------- documents as bytes / viewer / export ----------

interface DocContentFacade {
    suspend fun thumbnailPath(docId: String): String? // ensure + return cache path, null on failure

    suspend fun pageCount(docId: String): Int?

    suspend fun renderPage(docId: String, pageIndex: Int, maxDim: Int): ByteArray? // png

    suspend fun bakedCopyBytes(docId: String): ByteArray? // export/share with XMP baked

    suspend fun bakeInPlace(docId: String): BakeOutcome // explicit user action; emits relink

    suspend fun rawBytes(docId: String): ByteArray?

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
    /** The document is gone, trashed or missing. */
    DOCUMENT_UNAVAILABLE,

    /** The PDF cannot carry the metadata (unreadable, or the rewrite did not verify); it is untouched. */
    UNSUPPORTED_PDF,

    /** Writing the rewritten PDF or recording its new hash failed. */
    NOT_SAVED,
}
