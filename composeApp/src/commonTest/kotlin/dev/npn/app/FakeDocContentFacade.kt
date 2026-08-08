package dev.npn.app

import dev.npn.core.app.DocContentFacade

class FakeDocContentFacade : DocContentFacade {
    var pageCountValue: Int? = null
    var failPageCount: (() -> Nothing)? = null
    var bakeResult: Boolean = true
    var failBake: (() -> Nothing)? = null
    var bakedBytes: ByteArray? = null
    val bakedInPlace = mutableListOf<String>()

    override suspend fun thumbnailPath(docId: String): String? = null

    override suspend fun pageCount(docId: String): Int? {
        failPageCount?.invoke()
        return pageCountValue
    }

    override suspend fun renderPage(
        docId: String,
        pageIndex: Int,
        maxDim: Int,
    ): ByteArray? = null

    override suspend fun bakedCopyBytes(docId: String): ByteArray? = bakedBytes

    override suspend fun bakeInPlace(docId: String): Boolean {
        failBake?.invoke()
        bakedInPlace += docId
        return bakeResult
    }

    override suspend fun rawBytes(docId: String): ByteArray? = null

    var clearedThumbnails = 0

    override suspend fun clearThumbnailCache(): Int = clearedThumbnails

    var extractedTextValue: String? = null

    /** Per-doc extracted text; a docId present here wins over [extractedTextValue]. */
    var extractedTextByDoc: Map<String, String?> = emptyMap()

    override suspend fun extractedText(docId: String): String? =
        if (docId in extractedTextByDoc) extractedTextByDoc[docId] else extractedTextValue
}
