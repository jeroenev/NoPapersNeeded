package dev.npn.core.testing

import dev.npn.core.app.BakeOutcome
import dev.npn.core.app.DocContentFacade

/** Document content: canned page count, extracted text and bake results; nothing is rendered. */
class FakeDocContentFacade : DocContentFacade {
    /** Null models a page count that could not be read; the facade returns null, never throws. */
    var pageCountValue: Int? = null
    var bakeResult: BakeOutcome = BakeOutcome.Baked
    var failBake: (() -> Nothing)? = null
    var bakedBytes: ByteArray? = null
    val bakedInPlace = mutableListOf<String>()
    var clearedThumbnails = 0
    var extractedTextValue: String? = null

    /** Per-doc extracted text; a docId present here wins over [extractedTextValue]. */
    var extractedTextByDoc: Map<String, String?> = emptyMap()

    override suspend fun thumbnailPath(docId: String): String? = null

    override suspend fun pageCount(docId: String): Int? = pageCountValue

    override suspend fun renderPage(
        docId: String,
        pageIndex: Int,
        maxDim: Int,
    ): ByteArray? = null

    override suspend fun bakedCopyBytes(docId: String): ByteArray? = bakedBytes

    override suspend fun bakeInPlace(docId: String): BakeOutcome {
        failBake?.invoke()
        bakedInPlace += docId
        return bakeResult
    }

    override suspend fun rawBytes(docId: String): ByteArray? = null

    override suspend fun clearThumbnailCache(): Int = clearedThumbnails

    override suspend fun extractedText(docId: String): String? =
        if (docId in extractedTextByDoc) extractedTextByDoc[docId] else extractedTextValue
}
