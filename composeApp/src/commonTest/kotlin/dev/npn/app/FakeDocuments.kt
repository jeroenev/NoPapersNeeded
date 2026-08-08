package dev.npn.app

import dev.npn.core.app.AutoMatchRuleRepository
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SavedViewRepository
import dev.npn.core.app.TagInfo
import dev.npn.core.app.TagRepository
import dev.npn.core.db.DocumentEntity
import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

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
    trashed: Boolean = false,
): DocumentEntity =
    DocumentEntity(
        docId = docId,
        relPath = relPath,
        fileName = fileName,
        size = 0L,
        mtime = 0L,
        addedAt = 0L,
        reviewed = reviewed,
        correspondent = correspondent,
        docType = docType,
        docDate = docDate,
        title = title,
        trashed = trashed,
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
    val missing = MutableStateFlow<List<DocumentEntity>>(emptyList())
    val duplicates = MutableStateFlow<List<Pair<String, String>>>(emptyList())
    val correspondents = MutableStateFlow<List<String>>(emptyList())
    val docTypes = MutableStateFlow<List<String>>(emptyList())

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

    override fun observeMissing(): Flow<List<DocumentEntity>> = missing

    override fun observeDuplicates(): Flow<List<Pair<String, String>>> = duplicates

    val fieldKeys = MutableStateFlow<List<String>>(emptyList())

    /** Backing list returned by [history]; tests mutate it to drive the history panel. */
    var historyList: List<DocumentHistoryRecord> = emptyList()

    override fun observeCorrespondents(): Flow<List<String>> = correspondents

    override fun observeDocTypes(): Flow<List<String>> = docTypes

    override fun observeFieldKeys(): Flow<List<String>> = fieldKeys

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

    override fun observeSavedViews(): Flow<List<SavedView>> = views

    override suspend fun save(view: SavedView) {
        saved += view
    }

    override suspend fun delete(id: String) {
        deleted += id
    }

    override suspend fun exportJson(): String = "[]"

    override suspend fun importJson(text: String): Int = 0
}
