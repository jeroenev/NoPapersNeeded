package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import kotlinx.coroutines.flow.Flow

// ---------- read side ----------

data class DocListItem(
    val doc: DocumentEntity,
    val tags: List<String>,
    val snippet: String? = null, // only set for FTS query results
    val fields: Map<String, String> = emptyMap(), // custom metadata fields (key -> value)
)

data class TagInfo(
    val name: String,
    val color: String?,
    val icon: String?,
    val count: Int,
)

/** One indexed journal row, retaining diagnostics when its canonical event cannot be decoded. */
data class DocumentHistoryRecord(
    val hlc: Hlc,
    val deviceId: String,
    val operation: String,
    val event: JournalEvent?,
)

interface DocumentRepository {
    fun observeDocuments(
        filter: LibraryFilter,
        sort: LibrarySort,
    ): Flow<List<DocListItem>>

    fun observeDoc(docId: String): Flow<DocListItem?>

    fun observeUntaggedCount(): Flow<Int> // UNTAGGED_ONLY review count

    fun observeUnreviewedOrUntaggedCount(): Flow<Int> // INBOX_WORKFLOW review count

    fun observeTrashed(): Flow<List<DocListItem>>

    fun observeMissing(): Flow<List<DocumentEntity>>

    fun observeDuplicates(): Flow<List<Pair<String, String>>> // docId to extra relPath

    fun observeCorrespondents(): Flow<List<String>>

    fun observeDocTypes(): Flow<List<String>>

    /** Distinct custom-field keys in use across the library, for add-field autocomplete. */
    fun observeFieldKeys(): Flow<List<String>>

    /**
     * Autocomplete suggestions for a partial search query. Combines terms from the FTS5
     * vocabulary with tag/correspondent/docType values starting with the query prefix.
     */
    suspend fun searchSuggestions(
        query: String,
        limit: Int = 10,
    ): List<String>

    /**
     * Journal event history for one document, decoded and ordered by HLC. Read-only: the
     * underlying journal events are immutable once written.
     */
    suspend fun history(docId: String): List<DocumentHistoryRecord>
}

interface TagRepository {
    fun observeTags(): Flow<List<TagInfo>>

    suspend fun allTagNames(): List<String>
}

interface SavedViewRepository {
    fun observeSavedViews(): Flow<List<SavedView>>

    suspend fun save(view: SavedView)

    suspend fun delete(id: String)

    suspend fun exportJson(): String

    suspend fun importJson(text: String): Int // returns number imported
}
