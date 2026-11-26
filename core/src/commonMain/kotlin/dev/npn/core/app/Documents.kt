package dev.npn.core.app

import dev.npn.core.index.DuplicateReport
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.reviewMarkerIsAuthoritative
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.ocr.OcrState
import kotlinx.coroutines.flow.Flow

/**
 * The stable view of one document across this read seam. `dev.npn.core.db.DocumentEntity` — the
 * disposable index row — never crosses it; [DocumentRepositoryImpl] maps it here exactly once, so
 * a column change ripples no further than that mapping.
 *
 * Deliberately absent: `mtime`, which is index bookkeeping with no consumer outside `core`.
 */
data class Doc(
    val docId: String,
    val relPath: String,
    val fileName: String,
    /** The title field when it is non-blank, else [fileName]. Derived once here, never at a call site. */
    val displayTitle: String,
    val size: Long,
    val pageCount: Int?,
    /**
     * When the PDF says it was created, in epoch millis; the file's modification time when it names
     * none, or until the PDF has been read.
     */
    val createdAt: Long,
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
    /**
     * Writer key of the OCR sidecar currently indexed for search: [ocrEngine] alone cannot tell two
     * versions from the same engine apart. Null until first indexing, and on rows indexed before
     * the key was recorded.
     */
    val ocrSource: String?,
    /** User-pinned engine, or null for automatic best-engine selection. */
    val ocrEngineOverride: OcrEngineRef?,
    /** A retained tombstone, so an open editor can stop offering writes when deletion syncs in. */
    val deleted: Boolean = false,
) {
    // Named accessors for the well-known fields. They are lookups into [fields], nothing more; the
    // registry-driven UI reads the map directly, these exist for the seams that name a key.
    val title: String? get() = fields[FieldKeys.TITLE]
    val correspondent: String? get() = fields[FieldKeys.CORRESPONDENT]
    val docType: String? get() = fields[FieldKeys.DOC_TYPE]

    /** ISO-8601 local date, e.g. 2026-07-15. */
    val docDate: String? get() = fields[FieldKeys.DOC_DATE]
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

/**
 * Effective reviewed state for display. In [ReviewMode.UNTAGGED_ONLY] the marker is derived
 * (reviewed == has any tag); in [ReviewMode.INBOX_WORKFLOW] the stored reviewed flag is
 * authoritative (see [reviewMarkerIsAuthoritative]).
 */
fun DocListItem.effectiveReviewed(mode: ReviewMode): Boolean =
    if (mode.reviewMarkerIsAuthoritative) doc.reviewed else tags.isNotEmpty()

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

    /**
     * The live documents [query] finds (full text, file names and metadata, as [observeDocuments]
     * searches them), best match first, read once and at most [limit] of them: tags, fields and a
     * snippet are read for those alone, so it costs what it returns rather than the whole match.
     */
    suspend fun searchDocuments(
        query: String,
        limit: Int,
    ): List<DocListItem>

    /** How many documents [mode]'s review queue holds: those its [reviewQueueFilter] lists. */
    fun observeReviewQueueCount(mode: ReviewMode): Flow<Int>

    fun observeTrashed(): Flow<List<DocListItem>>

    fun observeMissing(): Flow<List<Doc>>

    /** The extra copies of documents' bytes the last rescan found, each by its document and path. */
    fun observeDuplicates(): Flow<List<DuplicateReport>>

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

    /**
     * The live documents among [docIds] with their tags and fields, read once and in no particular
     * order; an id whose document is not live is left out. Takes any number of ids.
     */
    suspend fun liveDocuments(docIds: List<String>): List<DocListItem>
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
