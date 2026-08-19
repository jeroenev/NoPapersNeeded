package dev.npn.core.app

import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.MetadataSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.db.DocumentEntity
import dev.npn.core.model.DocProperty
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

// Shared test doubles for the AI-edit suites: AiEditRunTest drives the interactive run,
// AiEditQueueTest the background one. Both need the same three collaborators, and the point of
// sharing them is that the two flows are asserted against IDENTICAL write and read behaviour.
//
// Narrow by design: only what the AI-edit code touches is wired; the rest throws.

internal fun doc(
    docId: String,
    title: String? = null,
    correspondent: String? = null,
    docDate: String? = null,
): DocumentEntity =
    DocumentEntity(
        docId = docId,
        relPath = "$docId.pdf",
        fileName = "$docId.pdf",
        size = 0L,
        mtime = 0L,
        addedAt = 0L,
        reviewed = false,
        correspondent = correspondent,
        docType = null,
        docDate = docDate,
        title = title,
        trashed = false,
    )

internal fun item(
    id: String,
    title: String? = null,
    correspondent: String? = null,
    docDate: String? = null,
): DocListItem = DocListItem(doc = doc(id, title, correspondent, docDate).toDoc(), tags = emptyList())

internal fun edit(
    addTags: List<TagSuggestion> = emptyList(),
    removeTags: List<String> = emptyList(),
    metadata: MetadataSuggestion = MetadataSuggestion.EMPTY,
) = AiEditSuggestion(addTags = addTags, removeTags = removeTags, metadata = metadata)

internal class FakeDocuments : DocumentRepository {
    val documents = MutableStateFlow<List<DocListItem>>(emptyList())

    /** Records every (filter, sort) the run subscribed with. */
    val observedFilters = mutableListOf<Pair<LibraryFilter, LibrarySort>>()

    override fun observeDocuments(
        filter: LibraryFilter,
        sort: LibrarySort,
    ): Flow<List<DocListItem>> {
        observedFilters += filter to sort
        return documents
    }

    override fun observeDoc(docId: String): Flow<DocListItem?> = error("unused")

    override fun observeUntaggedCount(): Flow<Int> = error("unused")

    override fun observeUnreviewedOrUntaggedCount(): Flow<Int> = error("unused")

    override fun observeTrashed(): Flow<List<DocListItem>> = error("unused")

    override fun observeMissing(): Flow<List<Doc>> = error("unused")

    override fun observeDuplicates(): Flow<List<Pair<String, String>>> = error("unused")

    override fun observeCorrespondents(): Flow<List<String>> = error("unused")

    override fun observeDocTypes(): Flow<List<String>> = error("unused")

    override fun observeFieldKeys(): Flow<List<String>> = error("unused")

    override suspend fun searchSuggestions(
        query: String,
        limit: Int,
    ): List<String> = error("unused")

    override suspend fun history(docId: String): List<DocumentHistoryRecord> = error("unused")
}

internal class FakeMeta : MetadataWriter {
    val addedTags = mutableListOf<Pair<List<String>, String>>()
    val removedTags = mutableListOf<Pair<List<String>, String>>()

    /** Field writes, in the order applied: one entry per document, never one per field. */
    val batchedEdits = mutableListOf<Pair<String, Map<DocProperty, String?>>>()

    /** addTag throws when any of these ids is in the call's docIds (per-doc partial failure). */
    var failAddTagForDocs: Set<String> = emptySet()

    /** removeTag throws when any of these ids is in the call's docIds. */
    var failRemoveTagForDocs: Set<String> = emptySet()

    /** applyEdits throws for these docIds (per-doc partial failure on the field write). */
    var failEditsForDocs: Set<String> = emptySet()

    override suspend fun addTag(
        docIds: List<String>,
        tag: String,
    ) {
        if (docIds.any { it in failAddTagForDocs }) error("addTag failed for $docIds")
        addedTags += docIds to tag
    }

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) {
        if (docIds.any { it in failRemoveTagForDocs }) error("removeTag failed for $docIds")
        removedTags += docIds to tag
    }

    override suspend fun applyEdits(
        docIds: List<String>,
        addTags: List<String>,
        properties: Map<DocProperty, String?>,
    ) {
        // Field values are per document, so an apply must never fold several into one call: that
        // would make one document's rejection lose the others' writes.
        val docId = docIds.single()
        if (docId in failEditsForDocs) error("applyEdits failed for $docId")
        batchedEdits += docId to properties
    }

    // Field writes must go through applyEdits: a setter per field would re-run the storage path
    // rules once per field and rename the document repeatedly for one apply.
    override suspend fun setTitle(
        docId: String,
        title: String?,
    ) = error("must batch through applyEdits")

    override suspend fun setCorrespondent(
        docIds: List<String>,
        value: String?,
    ) = error("must batch through applyEdits")

    override suspend fun setDocDate(
        docId: String,
        isoDate: String?,
    ) = error("must batch through applyEdits")

    override suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    ) = error("unused")

    override suspend fun setDocType(
        docIds: List<String>,
        value: String?,
    ) = error("unused")

    override suspend fun setNotes(
        docId: String,
        notes: String?,
    ) = error("unused")

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) = error("unused")
}

internal class FakeContent : DocContentFacade {
    /** Per-doc extracted text backing snippets; absent docs yield null. */
    var extractedTextByDoc: Map<String, String?> = emptyMap()

    override suspend fun extractedText(docId: String): String? = extractedTextByDoc[docId]

    override suspend fun thumbnailPath(docId: String): String? = error("unused")

    override suspend fun pageCount(docId: String): Int? = error("unused")

    override suspend fun renderPage(
        docId: String,
        pageIndex: Int,
        maxDim: Int,
    ): ByteArray? = error("unused")

    override suspend fun bakedCopyBytes(docId: String): ByteArray? = error("unused")

    override suspend fun bakeInPlace(docId: String): Boolean = error("unused")

    override suspend fun rawBytes(docId: String): ByteArray? = error("unused")

    override suspend fun clearThumbnailCache(): Int = error("unused")
}
