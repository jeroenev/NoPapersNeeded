package dev.npn.core.app

import dev.npn.core.model.FieldKeys
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.ocr.OcrState
import kotlinx.coroutines.flow.Flow

/**
 * The stable view of one document across this read seam. `dev.npn.core.db.DocumentEntity` — the
 * disposable index row — never leaves `core.db`; [DocumentRepositoryImpl] maps it here exactly
 * once, so a column change ripples no further than that mapping.
 *
 * Deliberately absent: `mtime` and `deleted`. They are index bookkeeping with no consumer outside
 * `core`.
 */
data class Doc(
    val docId: String,
    val relPath: String,
    val fileName: String,
    /** The title field when it is non-blank, else [fileName]. Derived once here, never at a call site. */
    val displayTitle: String,
    val size: Long,
    val pageCount: Int?,
    val addedAt: Long,
    val reviewed: Boolean,
    /**
     * Every field value on this document, keyed by registry key ([dev.npn.core.model.FieldKeys]
     * names the well-known ones), in canonical form. A cleared field is absent, never blank.
     */
    val fields: Map<String, String>,
    val trashed: Boolean,
    val trashedOrigPath: String?,
    val missing: Boolean,
    val ocrState: OcrState,
    /** Engine of the OCR sidecar currently indexed for search; null until first indexing. */
    val ocrEngine: OcrEngineRef?,
    /** User-pinned engine, or null for automatic best-engine selection. */
    val ocrEngineOverride: OcrEngineRef?,
) {
    // Named accessors for the well-known fields. They are lookups into [fields], nothing more; the
    // registry-driven UI reads the map directly, these exist for the seams that name a key.
    val title: String? get() = fields[FieldKeys.TITLE]
    val correspondent: String? get() = fields[FieldKeys.CORRESPONDENT]
    val docType: String? get() = fields[FieldKeys.DOC_TYPE]

    /** ISO-8601 local date, e.g. 2026-07-15. */
    val docDate: String? get() = fields[FieldKeys.DOC_DATE]
    val notes: String? get() = fields[FieldKeys.NOTES]
}

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
)

data class TagInfo(
    val name: String,
    val color: String?,
    val icon: String?,
    val count: Int,
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

    /**
     * Autocomplete suggestions for a partial search query. Combines terms from the FTS5
     * vocabulary with tag values and every TEXT field's values starting with the query prefix.
     */
    suspend fun searchSuggestions(
        query: String,
        limit: Int = 10,
    ): List<String>

    /**
     * The history of one document, in the journal's causal order. Read-only: the journal events it
     * is read from are immutable once written; one that cannot be decoded is kept as
     * [HistoryEntry.Undecodable].
     */
    suspend fun history(docId: String): List<DocumentHistoryRecord>

    /**
     * Tag names carried by each of [docIds], keyed by doc id and sorted alphabetically. Empty when
     * [docIds] is empty. Lets a caller read a selection's tags without depending on the result set
     * it was selected from, which a filter change can swap out from under it.
     */
    suspend fun tagsForDocuments(docIds: List<String>): Map<String, List<String>>
}

interface TagRepository {
    fun observeTags(): Flow<List<TagInfo>>

    suspend fun allTagNames(): List<String>
}

interface SavedViewRepository {
    fun observeSavedViews(): Flow<List<SavedView>>

    suspend fun save(view: SavedView)

    suspend fun delete(id: String)
}
