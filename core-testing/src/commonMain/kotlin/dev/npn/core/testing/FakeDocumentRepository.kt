package dev.npn.core.testing

import dev.npn.core.app.Doc
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.ocr.OcrState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A document as the repository reads it back: the well-known fields given here are its field map,
 * so `title`/`correspondent` read through it exactly as for a folded document.
 */
fun doc(
    docId: String,
    fileName: String = "$docId.pdf",
    relPath: String = fileName,
    reviewed: Boolean = false,
    correspondent: String? = null,
    docType: String? = null,
    docDate: String? = null,
    title: String? = null,
    notes: String? = null,
    trashed: Boolean = false,
    ocrState: OcrState = OcrState.NONE,
    ocrEngine: OcrEngineRef? = null,
    ocrSource: String? = null,
): Doc =
    Doc(
        docId = docId,
        relPath = relPath,
        fileName = fileName,
        displayTitle = title?.takeIf { it.isNotBlank() } ?: fileName,
        size = 0L,
        pageCount = null,
        addedAt = 0L,
        reviewed = reviewed,
        fields =
            buildMap {
                title?.let { put(FieldKeys.TITLE, it) }
                correspondent?.let { put(FieldKeys.CORRESPONDENT, it) }
                docType?.let { put(FieldKeys.DOC_TYPE, it) }
                docDate?.let { put(FieldKeys.DOC_DATE, it) }
                notes?.let { put(FieldKeys.NOTES, it) }
            },
        trashed = trashed,
        trashedOrigPath = null,
        missing = false,
        ocrState = ocrState,
        ocrEngine = ocrEngine,
        ocrSource = ocrSource,
        ocrEngineOverride = null,
    )

fun listItem(
    docId: String,
    tags: List<String> = emptyList(),
    snippet: String? = null,
): DocListItem = listItem(doc(docId), tags, snippet)

fun listItem(
    doc: Doc,
    tags: List<String> = emptyList(),
    snippet: String? = null,
): DocListItem = DocListItem(doc = doc, tags = tags, snippet = snippet)

/**
 * Documents backed by [MutableStateFlow]s, so tests can push new emissions. [observeDocuments]
 * narrows [documents] to the filter's docIds and filters nothing else; every document in
 * [documents] is live.
 */
class FakeDocumentRepository : DocumentRepository {
    val documents = MutableStateFlow<List<DocListItem>>(emptyList())
    val singleDoc = MutableStateFlow<DocListItem?>(null)
    val untaggedCount = MutableStateFlow(0)
    val unreviewedOrUntaggedCount = MutableStateFlow(0)
    val trashed = MutableStateFlow<List<DocListItem>>(emptyList())
    val missing = MutableStateFlow<List<Doc>>(emptyList())
    val duplicates = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    /** Records every (filter, sort) a caller subscribed with. */
    val observedFilters = mutableListOf<Pair<LibraryFilter, LibrarySort>>()

    /** [documents], narrowed to [LibraryFilter.docIds] when the filter names any; no other filtering. */
    override fun observeDocuments(
        filter: LibraryFilter,
        sort: LibrarySort,
    ): Flow<List<DocListItem>> {
        observedFilters += filter to sort
        if (filter.docIds.isEmpty()) return documents
        return documents.map { list -> list.filter { it.doc.docId in filter.docIds } }
    }

    override fun observeDoc(docId: String): Flow<DocListItem?> = singleDoc

    override fun observeReviewQueueCount(mode: ReviewMode): Flow<Int> =
        when (mode) {
            ReviewMode.UNTAGGED_ONLY -> untaggedCount
            ReviewMode.INBOX_WORKFLOW -> unreviewedOrUntaggedCount
        }

    override fun observeTrashed(): Flow<List<DocListItem>> = trashed

    override fun observeMissing(): Flow<List<Doc>> = missing

    override fun observeDuplicates(): Flow<List<Pair<String, String>>> = duplicates

    /** Backing list returned by [history]; tests mutate it to drive the history panel. */
    var historyList: List<DocumentHistoryRecord> = emptyList()

    var historyError: Throwable? = null
    var historyCalls: Int = 0

    override suspend fun history(docId: String): List<DocumentHistoryRecord> {
        historyCalls++
        historyError?.let { throw it }
        return historyList
    }

    /** Backing suggestions returned by [searchSuggestions]. */
    var suggestionsList: List<String> = emptyList()

    override suspend fun searchSuggestions(
        query: String,
        limit: Int,
    ): List<String> = suggestionsList.filter { it.startsWith(query, ignoreCase = true) }.take(limit)

    override suspend fun tagsForDocuments(docIds: List<String>): Map<String, List<String>> =
        documents.value
            .filter { it.doc.docId in docIds }
            .associate { it.doc.docId to it.tags }

    override suspend fun liveDocuments(docIds: List<String>): List<DocListItem> =
        documents.value.filter { it.doc.docId in docIds }
}
