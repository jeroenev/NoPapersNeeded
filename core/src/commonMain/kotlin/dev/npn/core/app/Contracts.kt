package dev.npn.core.app

import dev.npn.core.ai.AiFacade
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.SavedViewEntity
import dev.npn.core.index.ScanReport
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

// ---------- filtering / sorting ----------

// Contract between the service layer (implemented in dev.npn.core.app) and the UI layer
// (composeApp). UI code must depend only on these types plus the db entities they carry.

/** Single-value document fields whose absence can be filtered on; see [LibraryFilter.missingAny]. */
enum class DocField { TITLE, CORRESPONDENT, DOC_DATE }

@Serializable
data class LibraryFilter(
    val query: String? = null, // FTS query; null/blank = no text search
    val tagsAll: List<String> = emptyList(),
    val tagsAny: List<String> = emptyList(),
    val tagsNone: List<String> = emptyList(),
    val reviewed: Boolean? = null, // null = any; true/false filters the reviewed marker
    val correspondent: String? = null,
    val docType: String? = null,
    val docDateFrom: String? = null, // ISO yyyy-MM-dd, inclusive
    val docDateTo: String? = null,
    val addedFrom: Long? = null, // epoch millis, inclusive
    val addedTo: Long? = null,
    val folder: String? = null, // relPath prefix
    val untaggedOnly: Boolean = false,
    val unreviewedOrUntagged: Boolean = false, // INBOX_WORKFLOW review preset: !reviewed OR no tags
    // Documents where at least ONE of these fields is null/blank; empty = no such constraint.
    // OR-ed (unlike the AND-ed rest of the filter) so "still needs data" is expressible in one pass.
    val missingAny: List<DocField> = emptyList(),
) {
    val isEmpty: Boolean
        get() = this == LibraryFilter()
}

enum class SortField { NAME, ADDED, DOC_DATE, SIZE }

@Serializable
data class LibrarySort(
    val field: SortField = SortField.ADDED,
    val ascending: Boolean = false,
)

enum class ViewType { LIST, GRID }

@Serializable
data class SavedView(
    val id: String,
    val name: String,
    val filter: LibraryFilter = LibraryFilter(),
    val sort: LibrarySort = LibrarySort(),
    val viewType: ViewType = ViewType.LIST,
    val pinned: Boolean = false,
)

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

    /**
     * Live count of documents matching [filter] (same WHERE semantics as [observeDocuments]).
     * Unlike observing the list, this never materializes rows or attaches tags/fields, so it is
     * cheap even for large libraries.
     */
    fun observeCount(filter: LibraryFilter): Flow<Int>

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

// ---------- write side (journal-backed) ----------

interface MetadataWriter {
    suspend fun addTag(
        docIds: List<String>,
        tag: String,
    )

    suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    )

    suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    )

    suspend fun setCorrespondent(
        docIds: List<String>,
        value: String?,
    )

    suspend fun setDocType(
        docIds: List<String>,
        value: String?,
    )

    suspend fun setDocDate(
        docId: String,
        isoDate: String?,
    )

    suspend fun setTitle(
        docId: String,
        title: String?,
    )

    /** Set a free-text note; null or blank clears it. */
    suspend fun setNotes(
        docId: String,
        notes: String?,
    )

    /** Set (or clear, when [value] is null/blank) a custom metadata field [key] on [docId]. */
    suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    )

    /**
     * Pin the OCR engine whose version is used for search on [docId] (e.g. "tesseract",
     * "mlkit", "ai"), or clear the pin (null) for automatic best-engine selection. Synced
     * across devices as an LWW journal property.
     */
    suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    )

    suspend fun renameTag(
        from: String,
        to: String,
    )

    suspend fun mergeTag(
        from: String,
        to: String,
    )

    suspend fun deleteTag(name: String) // removes tag from all docs

    suspend fun setTagStyle(
        tag: String,
        color: String?,
        icon: String?,
    )

    suspend fun trashDoc(docId: String) // moves file to .npn/trash + doc-trash event

    suspend fun restoreDoc(docId: String)

    suspend fun deleteDocPermanently(docId: String) // deletes file + doc-delete tombstone

    suspend fun renameFile(
        docId: String,
        newFileName: String,
    )

    suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    )
}

// ---------- documents as bytes / viewer / export ----------

interface DocContentFacade {
    suspend fun thumbnailPath(docId: String): String? // ensure + return cache path, null on failure

    suspend fun pageCount(docId: String): Int?

    suspend fun renderPage(docId: String, pageIndex: Int, maxDim: Int): ByteArray? // png

    suspend fun bakedCopyBytes(docId: String): ByteArray? // export/share with XMP baked

    suspend fun bakeInPlace(docId: String): Boolean // explicit user action; emits relink

    suspend fun rawBytes(docId: String): ByteArray?

    suspend fun clearThumbnailCache(): Int // drop cached thumbnails; returns count cleared

    suspend fun extractedText(docId: String): String? // OCR/embedded full text, null if none yet
}

// ---------- session ----------

data class OcrStatus(
    val pending: Int,
    val running: Boolean,
)

/**
 * One stored OCR version of a document (an OCR sidecar). [active] marks the version whose
 * text is currently indexed for search on this device.
 */
data class OcrVersion(
    val engine: String,
    val deviceId: String,
    val lang: String,
    val createdAt: Long,
    val active: Boolean,
)

/** Everything scoped to one opened library folder. */
interface LibrarySession {
    val documents: DocumentRepository
    val tags: TagRepository
    val savedViews: SavedViewRepository
    val rules: AutoMatchRuleRepository
    val meta: MetadataWriter
    val content: DocContentFacade
    val ai: AiFacade
    val deviceId: String

    val scanProgress: Flow<ScanProgressUi?> // null when idle
    val lastScanReport: Flow<ScanReport?>
    val ocrStatus: Flow<OcrStatus>

    /** Library-wide review workflow, synced across devices via the journal. */
    val reviewMode: Flow<ReviewMode>

    suspend fun setReviewMode(mode: ReviewMode)

    /**
     * One-shot migration used when switching to INBOX_WORKFLOW. Marks documents reviewed:
     * [onlyTagged] true -> only documents that currently have at least one tag; false -> all live
     * documents. Writes reviewed=true prop-set events only where needed (unaffected docs keep the
     * default false). No-op set writes nothing.
     */
    suspend fun markReviewedForMigration(onlyTagged: Boolean)

    suspend fun rescan(): ScanReport

    suspend fun importPdf(
        source: dev.npn.core.fs.ExternalSource,
        suggestedName: String,
    ): ImportOutcome

    suspend fun importPdfBytes(
        bytes: ByteArray,
        suggestedName: String,
    ): ImportOutcome

    suspend fun importImagesAsPdf(
        images: List<ByteArray>,
        suggestedName: String,
    ): ImportOutcome

    suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
        keepCanonical: Boolean,
    )

    suspend fun applyRelink(
        oldDocId: String,
        newDocId: String,
    )

    suspend fun purgeTrash(olderThanDays: Int)

    /** Re-queue a single document for OCR (resets its state to pending and kicks the worker). */
    suspend fun reOcr(docId: String)

    /** All stored OCR versions of [docId], best-ranked first. */
    suspend fun ocrVersions(docId: String): List<OcrVersion>

    /**
     * Pin (or clear, when [engine] is null) the OCR engine used for search on [docId], then
     * immediately re-select and re-index the matching stored version on this device. The pin
     * itself syncs via the journal; other devices re-select on ingesting the event.
     */
    suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    )

    /**
     * Split a document into one new document per [segments] entry (0-based page ranges; must be
     * contiguous and cover all pages). Parts are imported as regular new documents (deduped,
     * OCR queued), tags and single-value properties are copied onto them, and the original is
     * moved to trash. The original file itself is never modified.
     */
    suspend fun splitPdf(
        docId: String,
        segments: List<IntRange>,
    ): SplitOutcome

    /** Start folder watching (desktop), OCR worker, periodic reconciliation. Idempotent. */
    fun start(scope: CoroutineScope)

    /** Stop and await all work owned by this session. Does not close environment-owned resources. */
    suspend fun close()
}

data class ScanProgressUi(
    val phase: String,
    val done: Int,
    val total: Int,
)

sealed interface ImportOutcome {
    data class Imported(
        val docId: String,
        val relPath: String,
    ) : ImportOutcome

    data class Duplicate(
        val existingDocId: String,
        val existingPath: String,
    ) : ImportOutcome

    data class Failed(
        val message: String,
    ) : ImportOutcome
}

sealed interface SplitOutcome {
    /** One docId per requested segment (an already-existing identical part reuses its docId). */
    data class Done(
        val partDocIds: List<String>,
    ) : SplitOutcome

    data class Failed(
        val message: String,
    ) : SplitOutcome
}

// ---------- settings (app-global, not per library) ----------

data class SettingsSnapshot(
    /** Stable app installation identity used only for owned staging-file cleanup and diagnostics. */
    val installationId: String,
    val libraryLocation: String? = null,
    val appLanguage: String? = null,
    val ocrLanguage: String = "eng",
    val trashRetentionDays: Int = TrashSettings.DEFAULT_TRASH_RETENTION_DAYS,
    val defaultViewType: ViewType = ViewType.GRID,
    val librarySort: LibrarySort = LibrarySort(),
    val gridColumnWidth: Int = GridSizing.DEFAULT_COLUMN_WIDTH,
    val landscapeListColumns: Boolean = false,
    val watchedFolder: String? = null,
    val watchedFolderDeleteAfterImport: Boolean = false,
    val storagePathTemplate: String? = null,
    val storagePathEnabled: Boolean = false,
    val autoMatchRulesJson: String = "[]",
    val aiEnabled: Boolean = false,
    val aiBaseUrl: String = "",
    val aiApiKey: String = "",
    val aiModel: String = "",
    val aiMaxTagsPerDoc: Int = AiTagging.DEFAULT_TAGS_PER_DOC,
    val aiMaxNewTagsPerDoc: Int = AiTagging.DEFAULT_NEW_TAGS_PER_DOC,
)

interface SettingsRepository {
    val settings: Flow<SettingsSnapshot>

    suspend fun setLibraryLocation(location: String?)

    /**
     * App interface language override as an ISO 639-1 code (e.g. "en", "nl"), or null to follow
     * the system locale. Device-local by design: language is a per-user UI preference.
     */
    suspend fun setAppLanguage(lang: String?)

    suspend fun setOcrLanguage(lang: String)

    suspend fun setTrashRetentionDays(days: Int)

    suspend fun setDefaultViewType(v: ViewType)

    suspend fun setLibrarySort(sort: LibrarySort)

    suspend fun setGridColumnWidth(dp: Int)

    suspend fun setLandscapeListColumns(enabled: Boolean)

    /**
     * Folder watched for new PDFs to auto-import (e.g. a scanner app's output folder). Desktop:
     * absolute path; android: SAF tree uri string (persistable permission taken by the caller).
     * Null = feature disabled. Device-local by design: paths/uris never make sense on another
     * device, so this must not sync through the journal.
     */
    suspend fun setWatchedFolder(location: String?)

    /** Whether source files in the watched folder are removed after a successful import. Default false. */
    suspend fun setWatchedFolderDeleteAfterImport(enabled: Boolean)

    /** Storage path template, e.g. `{correspondent}/{doc_date:yyyy}/{title}.pdf`. Null = unset. */
    suspend fun setStoragePathTemplate(template: String?)

    /** Whether automatic storage path rule application is enabled. Default false. */
    suspend fun setStoragePathEnabled(enabled: Boolean)

    /** Durable device-local JSON source for auto-match rules; Room only projects this value. */
    suspend fun setAutoMatchRulesJson(json: String)

    // --- AI assistant (device-local by design: the API key must never sync via the journal) ---

    /** Master switch for all AI features. Default false. */
    suspend fun setAiEnabled(enabled: Boolean)

    /** OpenAI-compatible server root, e.g. `https://api.openai.com` or `http://localhost:11434`. */
    suspend fun setAiBaseUrl(url: String)

    /** Bearer token for the endpoint; blank for local servers without auth. */
    suspend fun setAiApiKey(key: String)

    /** Model id as the endpoint knows it, e.g. `gpt-4.1-mini` or `qwen3:8b`. */
    suspend fun setAiModel(model: String)

    /** Max tag suggestions produced per document. Default 8, coerced into [AiTagging.RANGE]. */
    suspend fun setAiMaxTagsPerDoc(value: Int)

    /**
     * Max genuinely-new (non-vocabulary) tags proposed per document. Default 5, coerced into
     * [AiTagging.NEW_TAGS_RANGE] (0 disables proposing new tags entirely).
     */
    suspend fun setAiMaxNewTagsPerDoc(value: Int)
}

internal object SettingsKeys {
    const val LIBRARY_LOCATION = "libraryLocation"
    const val APP_LANGUAGE = "appLanguage"
    const val OCR_LANGUAGE = "ocrLanguage"
    const val TRASH_RETENTION_DAYS = "trashRetentionDays"
    const val DEFAULT_VIEW_TYPE = "defaultViewType"
    const val LIBRARY_SORT_FIELD = "librarySortField"
    const val LIBRARY_SORT_ASCENDING = "librarySortAscending"
    const val GRID_COLUMN_WIDTH = "gridColumnWidth"
    const val LANDSCAPE_LIST_COLUMNS = "landscapeListColumns"
    const val INSTALLATION_ID = "installationId"
    const val WATCHED_FOLDER = "watchedFolder"
    const val WATCHED_FOLDER_DELETE_AFTER_IMPORT = "watchedFolderDeleteAfterImport"
    const val STORAGE_PATH_TEMPLATE = "storagePathTemplate"
    const val STORAGE_PATH_ENABLED = "storagePathEnabled"
    const val AUTO_MATCH_RULES_JSON = "autoMatchRulesJson"
    const val AI_ENABLED = "aiEnabled"
    const val AI_BASE_URL = "aiBaseUrl"
    const val AI_API_KEY = "aiApiKey"
    const val AI_MODEL = "aiModel"
    const val AI_MAX_TAGS_PER_DOC = "aiMaxTagsPerDoc"
    const val AI_MAX_NEW_TAGS_PER_DOC = "aiMaxNewTagsPerDoc"
}

/** Bounds for the user-adjustable "max suggested tags per document" setting. */
object AiTagging {
    const val MIN_TAGS_PER_DOC = 1
    const val MAX_TAGS_PER_DOC = 15
    const val DEFAULT_TAGS_PER_DOC = 8
    val RANGE = MIN_TAGS_PER_DOC..MAX_TAGS_PER_DOC

    const val MIN_NEW_TAGS_PER_DOC = 0
    const val MAX_NEW_TAGS_PER_DOC = 10
    const val DEFAULT_NEW_TAGS_PER_DOC = 5

    /** Allowed range for the new-tag cap; 0 means never propose brand-new tags. */
    val NEW_TAGS_RANGE = MIN_NEW_TAGS_PER_DOC..MAX_NEW_TAGS_PER_DOC

    /**
     * Bounds for how many documents one bulk-tagging run processes. This cap is per run and
     * adjustable on the AI tagging page (Idle phase); the remainder carries over to the next batch.
     */
    const val MIN_BATCH_DOCS = 1
    const val MAX_BATCH_DOCS = 100
    const val DEFAULT_BATCH_DOCS = 25
    val BATCH_RANGE = MIN_BATCH_DOCS..MAX_BATCH_DOCS
}

object TrashSettings {
    /** Days a trashed document is kept before an automatic purge removes it. */
    const val DEFAULT_TRASH_RETENTION_DAYS = 30
}

/** Bounds for the user-adjustable grid cell width (dp). */
object GridSizing {
    const val MIN_COLUMN_WIDTH = 100
    const val MAX_COLUMN_WIDTH = 480
    const val DEFAULT_COLUMN_WIDTH = 160
}
