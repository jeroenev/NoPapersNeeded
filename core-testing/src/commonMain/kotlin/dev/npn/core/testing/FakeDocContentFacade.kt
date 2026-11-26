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

    /**
     * What [streamDocument] streams, [streamChunkSize] bytes at a time; null models a document whose
     * file could not be read (it streams nothing and returns false).
     */
    var documentBytesValue: ByteArray? = null
    var streamChunkSize: Int = DEFAULT_CHUNK

    /** How many times [streamDocument] was called: a print reads the document only when asked. */
    var documentStreams = 0
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

    override suspend fun streamDocument(
        docId: String,
        sink: (ByteArray, Int) -> Unit,
    ): Boolean {
        documentStreams++
        val bytes = documentBytesValue ?: return false
        for (offset in bytes.indices step streamChunkSize) {
            val chunk = bytes.copyOfRange(offset, minOf(offset + streamChunkSize, bytes.size))
            sink(chunk, chunk.size)
        }
        return true
    }

    override suspend fun bakedCopyBytes(docId: String): ByteArray? = bakedBytes

    override suspend fun bakeInPlace(docId: String): BakeOutcome {
        failBake?.invoke()
        bakedInPlace += docId
        return bakeResult
    }

    override suspend fun clearThumbnailCache(): Int = clearedThumbnails

    override suspend fun extractedText(docId: String): String? =
        if (docId in extractedTextByDoc) extractedTextByDoc[docId] else extractedTextValue

    private companion object {
        const val DEFAULT_CHUNK = 64 * 1024
    }
}
