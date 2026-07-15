package dev.npn.core.app

import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.SavedViewEntity
import dev.npn.core.index.ScanReport
import dev.npn.core.model.DocStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/**
 * Contract between the service layer (implemented in dev.npn.core.app) and the UI layer
 * (composeApp). UI code must depend only on these types plus the db entities they carry.
 */

// ---------- filtering / sorting ----------

@Serializable
data class LibraryFilter(
    val query: String? = null,            // FTS query; null/blank = no text search
    val tagsAll: List<String> = emptyList(),
    val tagsAny: List<String> = emptyList(),
    val tagsNone: List<String> = emptyList(),
    val status: DocStatus? = null,
    val correspondent: String? = null,
    val docType: String? = null,
    val docDateFrom: String? = null,      // ISO yyyy-MM-dd, inclusive
    val docDateTo: String? = null,
    val addedFrom: Long? = null,          // epoch millis, inclusive
    val addedTo: Long? = null,
    val folder: String? = null,           // relPath prefix
    val untaggedOnly: Boolean = false,
) {
    val isEmpty: Boolean
        get() = this == LibraryFilter()
}

enum class SortField { NAME, ADDED, DOC_DATE, SIZE }

@Serializable
data class LibrarySort(val field: SortField = SortField.ADDED, val ascending: Boolean = false)

enum class ViewType { LIST, GRID, KANBAN }

enum class KanbanGrouping { STATUS, CORRESPONDENT, DOC_TYPE, TAGS }

@Serializable
data class SavedView(
    val id: String,
    val name: String,
    val filter: LibraryFilter = LibraryFilter(),
    val sort: LibrarySort = LibrarySort(),
    val viewType: ViewType = ViewType.LIST,
    val kanbanGrouping: KanbanGrouping = KanbanGrouping.STATUS,
    val kanbanTags: List<String> = emptyList(),  // lanes when grouping == TAGS
    val pinned: Boolean = false,
)

// ---------- read side ----------

data class DocListItem(
    val doc: DocumentEntity,
    val tags: List<String>,
    val snippet: String? = null,          // only set for FTS query results
)

data class TagInfo(val name: String, val color: String?, val icon: String?, val count: Int)

interface DocumentRepository {
    fun observeDocuments(filter: LibraryFilter, sort: LibrarySort): Flow<List<DocListItem>>
    fun observeDoc(docId: String): Flow<DocListItem?>
    fun observeInboxCount(): Flow<Int>
    fun observeUntaggedCount(): Flow<Int>
    fun observeTrashed(): Flow<List<DocListItem>>
    fun observeMissing(): Flow<List<DocumentEntity>>
    fun observeDuplicates(): Flow<List<Pair<String, String>>>  // docId to extra relPath
    fun observeCorrespondents(): Flow<List<String>>
    fun observeDocTypes(): Flow<List<String>>
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
    suspend fun importJson(json: String): Int   // returns number imported
}

// ---------- write side (journal-backed) ----------

interface MetadataWriter {
    suspend fun addTag(docIds: List<String>, tag: String)
    suspend fun removeTag(docIds: List<String>, tag: String)
    suspend fun setStatus(docIds: List<String>, status: DocStatus)
    suspend fun setCorrespondent(docIds: List<String>, value: String?)
    suspend fun setDocType(docIds: List<String>, value: String?)
    suspend fun setDocDate(docId: String, isoDate: String?)
    suspend fun setTitle(docId: String, title: String?)
    suspend fun renameTag(from: String, to: String)
    suspend fun mergeTag(from: String, to: String)
    suspend fun deleteTag(name: String)             // removes tag from all docs
    suspend fun setTagStyle(tag: String, color: String?, icon: String?)
    suspend fun trashDoc(docId: String)             // moves file to .npn/trash + doc-trash event
    suspend fun restoreDoc(docId: String)
    suspend fun deleteDocPermanently(docId: String) // deletes file + doc-delete tombstone
    suspend fun renameFile(docId: String, newFileName: String)
    suspend fun moveFile(docId: String, newDirRelPath: String)
}

// ---------- documents as bytes / viewer / export ----------

interface DocContentFacade {
    suspend fun thumbnailPath(docId: String): String?              // ensure + return cache path, null on failure
    suspend fun pageCount(docId: String): Int?
    suspend fun renderPage(docId: String, pageIndex: Int, maxDim: Int): ByteArray?  // png
    suspend fun bakedCopyBytes(docId: String): ByteArray?          // export/share with XMP baked
    suspend fun bakeInPlace(docId: String): Boolean                // explicit user action; emits relink
    suspend fun rawBytes(docId: String): ByteArray?
}

// ---------- session ----------

data class OcrStatus(val pending: Int, val running: Boolean)

/** Everything scoped to one opened library folder. */
interface LibrarySession {
    val documents: DocumentRepository
    val tags: TagRepository
    val savedViews: SavedViewRepository
    val meta: MetadataWriter
    val content: DocContentFacade
    val deviceId: String

    val scanProgress: Flow<ScanProgressUi?>        // null when idle
    val lastScanReport: Flow<ScanReport?>
    val ocrStatus: Flow<OcrStatus>

    suspend fun rescan(): ScanReport
    suspend fun importPdf(source: dev.npn.core.fs.ExternalSource, suggestedName: String): ImportOutcome
    suspend fun importPdfBytes(bytes: ByteArray, suggestedName: String): ImportOutcome
    suspend fun importImagesAsPdf(images: List<ByteArray>, suggestedName: String): ImportOutcome

    suspend fun resolveDuplicate(docId: String, extraRelPath: String, keepCanonical: Boolean)
    suspend fun applyRelink(oldDocId: String, newDocId: String)
    suspend fun purgeTrash(olderThanDays: Int)

    /** Re-queue a single document for OCR (resets its state to pending and kicks the worker). */
    suspend fun reOcr(docId: String)

    /** Start folder watching (desktop), OCR worker, periodic reconciliation. Idempotent. */
    fun start(scope: CoroutineScope)
    fun close()
}

data class ScanProgressUi(val phase: String, val done: Int, val total: Int)

sealed interface ImportOutcome {
    data class Imported(val docId: String, val relPath: String) : ImportOutcome
    data class Duplicate(val existingDocId: String, val existingPath: String) : ImportOutcome
    data class Failed(val message: String) : ImportOutcome
}

// ---------- settings (app-global, not per library) ----------

interface SettingsRepository {
    val libraryLocation: Flow<String?>   // desktop: absolute path; android: SAF tree uri string
    suspend fun setLibraryLocation(location: String?)
    val ocrLanguage: Flow<String>        // tesseract/mlkit lang code, default "eng"
    suspend fun setOcrLanguage(lang: String)
    val trashRetentionDays: Flow<Int>    // default 30
    suspend fun setTrashRetentionDays(days: Int)
    val defaultViewType: Flow<ViewType>
    suspend fun setDefaultViewType(v: ViewType)
}
