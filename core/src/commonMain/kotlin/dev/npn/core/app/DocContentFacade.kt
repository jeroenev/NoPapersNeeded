package dev.npn.core.app

// ---------- documents as bytes / viewer / export ----------

interface DocContentFacade {
    suspend fun thumbnailPath(docId: String): String? // ensure + return cache path, null on failure

    suspend fun pageCount(docId: String): Int?

    suspend fun renderPage(docId: String, pageIndex: Int, maxDim: Int): ByteArray? // png

    suspend fun bakedCopyBytes(docId: String): ByteArray? // export/share with XMP baked

    suspend fun bakeInPlace(docId: String): Boolean // explicit user action; emits relink

    suspend fun rawBytes(docId: String): ByteArray?

    suspend fun clearThumbnailCache(): Int // drop cached thumbnails; returns count cleared

    suspend fun extractedText(docId: String): String? // OCR/embedded full text, null if none yet
}
