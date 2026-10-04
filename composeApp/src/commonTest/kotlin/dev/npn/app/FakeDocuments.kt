package dev.npn.app

import dev.npn.core.app.AutoMatchRuleRepository
import dev.npn.core.app.Doc
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SavedViewRepository
import dev.npn.core.app.TagInfo
import dev.npn.core.app.TagRepository
import dev.npn.core.model.FieldKeys
import dev.npn.core.ocr.OcrState
import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Read-side test doubles: documents, tags, saved views and auto-match rules. Everything is backed
 * by [MutableStateFlow]s (so tests can push new emissions) and public mutable fields for behavior
 * injection.
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
        ocrEngine = null,
        ocrEngineOverride = null,
    )

fun listItem(
    docId: String,
    tags: List<String> = emptyList(),
    snippet: String? = null,
): DocListItem = DocListItem(doc = doc(docId), tags = tags, snippet = snippet)

class FakeDocumentRepository : DocumentRepository {
    val documents = MutableStateFlow<List<DocListItem>>(emptyList())
    val singleDoc = MutableStateFlow<DocListItem?>(null)
    val untaggedCount = MutableStateFlow(0)
    val unreviewedOrUntaggedCount = MutableStateFlow(0)
    val trashed = MutableStateFlow<List<DocListItem>>(emptyList())
    val missing = MutableStateFlow<List<Doc>>(emptyList())
    val duplicates = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    /** Records every (filter, sort) the view model subscribed with. */
    val observedFilters = mutableListOf<Pair<LibraryFilter, LibrarySort>>()

    override fun observeDocuments(
        filter: LibraryFilter,
        sort: LibrarySort,
    ): Flow<List<DocListItem>> {
        observedFilters += filter to sort
        return documents
    }

    override fun observeDoc(docId: String): Flow<DocListItem?> = singleDoc

    override fun observeUntaggedCount(): Flow<Int> = untaggedCount

    override fun observeUnreviewedOrUntaggedCount(): Flow<Int> = unreviewedOrUntaggedCount

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
}

class FakeTagRepository : TagRepository {
    val tags = MutableStateFlow<List<TagInfo>>(emptyList())
    var tagNames: List<String> = emptyList()

    override fun observeTags(): Flow<List<TagInfo>> = tags

    override suspend fun allTagNames(): List<String> = tagNames
}

class FakeAutoMatchRuleRepository : AutoMatchRuleRepository {
    val rules = MutableStateFlow<List<AutoMatchRule>>(emptyList())
    val saved = mutableListOf<AutoMatchRule>()
    val deletedIds = mutableListOf<String>()
    val enabledSets = mutableListOf<Pair<String, Boolean>>()

    override fun observeRules(): Flow<List<AutoMatchRule>> = rules

    override suspend fun save(rule: AutoMatchRule): String {
        saved += rule
        rules.value = rules.value.filter { it.id != rule.id } + rule
        return rule.id.ifBlank { "rule-${saved.size}" }
    }

    override suspend fun delete(id: String) {
        deletedIds += id
    }

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        enabledSets += id to enabled
    }
}

class FakeSavedViewRepository : SavedViewRepository {
    val views = MutableStateFlow<List<SavedView>>(emptyList())
    val saved = mutableListOf<SavedView>()
    val deleted = mutableListOf<String>()

    /** If set, save throws this. */
    var failSave: (() -> Nothing)? = null

    /** If set, delete throws this. */
    var failDelete: (() -> Nothing)? = null

    /** If set, the saved-views flow throws this when collected. */
    var failObserve: Throwable? = null

    override fun observeSavedViews(): Flow<List<SavedView>> =
        failObserve?.let { failure -> flow { throw failure } } ?: views

    override suspend fun save(view: SavedView) {
        failSave?.invoke()
        saved += view
    }

    override suspend fun delete(id: String) {
        failDelete?.invoke()
        deleted += id
    }
}
