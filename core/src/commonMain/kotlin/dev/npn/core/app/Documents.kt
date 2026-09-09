package dev.npn.core.app

import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.ocr.OcrState
import kotlinx.coroutines.flow.Flow

/**
 * The stable view of one document across this read seam. `dev.npn.core.db.DocumentEntity` — the
 * disposable index row — never leaves `core.db`; [DocumentRepositoryImpl] maps it here exactly
 * once, so a column change ripples no further than that mapping.
 *
 * Deliberately absent: `mtime`, `deleted` and `aiEnrichedAt`. They are index bookkeeping with no
 * consumer outside `core`.
 */
data class Doc(
    val docId: String,
    val relPath: String,
    val fileName: String,
    /** [title] when it is non-blank, else [fileName]. Derived once here, never at a call site. */
    val displayTitle: String,
    /** The raw stored title; null/blank means "untitled". The metadata panel edits this value. */
    val title: String?,
    val size: Long,
    val pageCount: Int?,
    val addedAt: Long,
    val reviewed: Boolean,
    val correspondent: String?,
    val docType: String?,
    /** ISO-8601 local date, e.g. 2026-07-15. */
    val docDate: String?,
    val notes: String?,
    val trashed: Boolean,
    val trashedOrigPath: String?,
    val missing: Boolean,
    val ocrState: OcrState,
    /** Engine of the OCR sidecar currently indexed for search; null until first indexing. */
    val ocrEngine: OcrEngineRef?,
    /** User-pinned engine, or null for automatic best-engine selection. */
    val ocrEngineOverride: OcrEngineRef?,
)

/**
 * The one rule for "what do we call this document": its title when it has one, else the file name.
 * Applied when a [Doc] is built and by the few other read types that carry a title/file-name pair,
 * so no UI call site repeats it.
 */
internal fun displayTitleOf(
    title: String?,
    fileName: String,
): String = title?.takeIf { it.isNotBlank() } ?: fileName

data class DocListItem(
    val doc: Doc,
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

    fun observeMissing(): Flow<List<Doc>>

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
