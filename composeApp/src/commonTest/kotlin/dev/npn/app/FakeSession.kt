package dev.npn.app

import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.OcrStatus
import dev.npn.core.app.SavedView
import dev.npn.core.app.SavedViewRepository
import dev.npn.core.app.ScanProgressUi
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.SplitOutcome
import dev.npn.core.app.TagInfo
import dev.npn.core.app.TagRepository
import dev.npn.core.app.ViewType
import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOf

/**
 * Test doubles for [LibrarySession] and its sub-interfaces. Everything is backed by
 * [MutableStateFlow]s (so tests can push new emissions) and public mutable fields for behavior
 * injection. Failure injection is done by assigning a throwing lambda to the relevant `on*` hook.
 *
 * Kept minimal: only what the view models under test call is wired; unused members throw or return
 * inert defaults.
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
): DocumentEntity = DocumentEntity(
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

fun listItem(docId: String, tags: List<String> = emptyList(), snippet: String? = null): DocListItem =
    DocListItem(doc = doc(docId), tags = tags, snippet = snippet)

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

    override fun observeDocuments(filter: LibraryFilter, sort: LibrarySort): Flow<List<DocListItem>> {
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

    override fun observeCorrespondents(): Flow<List<String>> = correspondents
    override fun observeDocTypes(): Flow<List<String>> = docTypes
    override fun observeFieldKeys(): Flow<List<String>> = fieldKeys
}

class FakeTagRepository : TagRepository {
    val tags = MutableStateFlow<List<TagInfo>>(emptyList())
    var tagNames: List<String> = emptyList()

    override fun observeTags(): Flow<List<TagInfo>> = tags
    override suspend fun allTagNames(): List<String> = tagNames
}

class FakeSavedViewRepository : SavedViewRepository {
    val views = MutableStateFlow<List<SavedView>>(emptyList())
    val saved = mutableListOf<SavedView>()
    val deleted = mutableListOf<String>()

    override fun observeSavedViews(): Flow<List<SavedView>> = views
    override suspend fun save(view: SavedView) { saved += view }
    override suspend fun delete(id: String) { deleted += id }
    override suspend fun exportJson(): String = "[]"
    override suspend fun importJson(text: String): Int = 0
}

/**
 * Records every write and lets tests fail a specific op. Each `on*` hook throws if set; the
 * recorded call lists let tests assert what was invoked. [failTrashDocIds] fails [trashDoc] for
 * exactly those ids (used to test per-doc partial failure).
 */
class FakeMetadataWriter : MetadataWriter {
    val addedTags = mutableListOf<Pair<List<String>, String>>()
    val removedTags = mutableListOf<Pair<List<String>, String>>()
    val reviewedSets = mutableListOf<Pair<List<String>, Boolean>>()
    val trashedDocs = mutableListOf<String>()
    val restoredDocs = mutableListOf<String>()
    val deletedDocs = mutableListOf<String>()
    val titleSets = mutableListOf<Pair<String, String?>>()
    val correspondentSets = mutableListOf<Pair<List<String>, String?>>()
    val docTypeSets = mutableListOf<Pair<List<String>, String?>>()
    val docDateSets = mutableListOf<Pair<String, String?>>()
    val fieldSets = mutableListOf<Triple<String, String, String?>>()
    val renamedTags = mutableListOf<Pair<String, String>>()
    val mergedTags = mutableListOf<Pair<String, String>>()
    val deletedTags = mutableListOf<String>()
    val tagStyles = mutableListOf<Triple<String, String?, String?>>()
    val renamedFiles = mutableListOf<Pair<String, String>>()
    val movedFiles = mutableListOf<Pair<String, String>>()

    /** If set, every batched op (addTag/removeTag/setReviewed) throws this. */
    var failBatchOps: (() -> Nothing)? = null

    /** trashDoc throws for these ids only. */
    var failTrashDocIds: Set<String> = emptySet()

    /** If set, setTitle throws. */
    var failSetTitle: (() -> Nothing)? = null

    /** If set, setField throws. */
    var failSetField: (() -> Nothing)? = null

    /** If set, restoreDoc throws. */
    var failRestore: (() -> Nothing)? = null

    /** If set, every tag-admin op (renameTag/mergeTag/deleteTag/setTagStyle) throws. */
    var failTagAdmin: (() -> Nothing)? = null

    override suspend fun addTag(docIds: List<String>, tag: String) {
        failBatchOps?.invoke()
        addedTags += docIds to tag
    }

    override suspend fun removeTag(docIds: List<String>, tag: String) {
        failBatchOps?.invoke()
        removedTags += docIds to tag
    }

    override suspend fun setReviewed(docIds: List<String>, reviewed: Boolean) {
        failBatchOps?.invoke()
        reviewedSets += docIds to reviewed
    }

    override suspend fun setCorrespondent(docIds: List<String>, value: String?) {
        correspondentSets += docIds to value
    }

    override suspend fun setDocType(docIds: List<String>, value: String?) {
        docTypeSets += docIds to value
    }

    override suspend fun setDocDate(docId: String, isoDate: String?) {
        docDateSets += docId to isoDate
    }

    override suspend fun setTitle(docId: String, title: String?) {
        failSetTitle?.invoke()
        titleSets += docId to title
    }

    override suspend fun setField(docId: String, key: String, value: String?) {
        failSetField?.invoke()
        fieldSets += Triple(docId, key, value)
    }

    override suspend fun renameTag(from: String, to: String) {
        failTagAdmin?.invoke()
        renamedTags += from to to
    }

    override suspend fun mergeTag(from: String, to: String) {
        failTagAdmin?.invoke()
        mergedTags += from to to
    }

    override suspend fun deleteTag(name: String) {
        failTagAdmin?.invoke()
        deletedTags += name
    }

    override suspend fun setTagStyle(tag: String, color: String?, icon: String?) {
        failTagAdmin?.invoke()
        tagStyles += Triple(tag, color, icon)
    }

    override suspend fun trashDoc(docId: String) {
        if (docId in failTrashDocIds) error("trash failed for $docId")
        trashedDocs += docId
    }

    override suspend fun restoreDoc(docId: String) {
        failRestore?.invoke()
        restoredDocs += docId
    }
    override suspend fun deleteDocPermanently(docId: String) { deletedDocs += docId }
    override suspend fun renameFile(docId: String, newFileName: String) { renamedFiles += docId to newFileName }
    override suspend fun moveFile(docId: String, newDirRelPath: String) { movedFiles += docId to newDirRelPath }
}

class FakeDocContentFacade : DocContentFacade {
    var pageCountValue: Int? = null
    var failPageCount: (() -> Nothing)? = null
    var bakeResult: Boolean = true
    var failBake: (() -> Nothing)? = null
    var bakedBytes: ByteArray? = null
    val bakedInPlace = mutableListOf<String>()

    override suspend fun thumbnailPath(docId: String): String? = null
    override suspend fun pageCount(docId: String): Int? {
        failPageCount?.invoke()
        return pageCountValue
    }

    override suspend fun renderPage(docId: String, pageIndex: Int, maxDim: Int): ByteArray? = null
    override suspend fun bakedCopyBytes(docId: String): ByteArray? = bakedBytes
    override suspend fun bakeInPlace(docId: String): Boolean {
        failBake?.invoke()
        bakedInPlace += docId
        return bakeResult
    }

    override suspend fun rawBytes(docId: String): ByteArray? = null

    var clearedThumbnails = 0
    override suspend fun clearThumbnailCache(): Int = clearedThumbnails

    var extractedTextValue: String? = null
    override suspend fun extractedText(docId: String): String? = extractedTextValue
}

class FakeLibrarySession : LibrarySession {
    override val documents = FakeDocumentRepository()
    override val tags = FakeTagRepository()
    override val savedViews = FakeSavedViewRepository()
    override val meta = FakeMetadataWriter()
    override val content = FakeDocContentFacade()
    override val deviceId: String = "test-device"

    val scanProgressFlow = MutableStateFlow<ScanProgressUi?>(null)
    val lastScanReportFlow = MutableStateFlow<ScanReport?>(null)
    val ocrStatusFlow = MutableStateFlow(OcrStatus(0, false))
    val reviewModeFlow = MutableStateFlow(ReviewMode.DEFAULT)

    override val scanProgress: Flow<ScanProgressUi?> get() = scanProgressFlow
    override val lastScanReport: Flow<ScanReport?> get() = lastScanReportFlow
    override val ocrStatus: Flow<OcrStatus> get() = ocrStatusFlow
    override val reviewMode: Flow<ReviewMode> get() = reviewModeFlow

    val reviewModeSets = mutableListOf<ReviewMode>()
    val migrations = mutableListOf<Boolean>()
    var rescanReport: ScanReport = emptyScanReport()
    var failRescan: (() -> Nothing)? = null

    /** Import outcomes, consumed in order per call; falls back to the last one when exhausted. */
    var importOutcomes: List<ImportOutcome> = listOf(ImportOutcome.Imported("x", "x.pdf"))
    private var importIdx = 0
    var failImport: (() -> Nothing)? = null
    val reOcrIds = mutableListOf<String>()
    var failReOcr: (() -> Nothing)? = null

    override suspend fun setReviewMode(mode: ReviewMode) {
        reviewModeSets += mode
        reviewModeFlow.value = mode
    }

    override suspend fun markReviewedForMigration(onlyTagged: Boolean) { migrations += onlyTagged }

    override suspend fun rescan(): ScanReport {
        failRescan?.invoke()
        return rescanReport
    }

    private fun nextOutcome(): ImportOutcome {
        failImport?.invoke()
        val o = importOutcomes.getOrElse(importIdx) { importOutcomes.last() }
        importIdx++
        return o
    }

    override suspend fun importPdf(source: ExternalSource, suggestedName: String): ImportOutcome = nextOutcome()
    override suspend fun importPdfBytes(bytes: ByteArray, suggestedName: String): ImportOutcome = nextOutcome()
    override suspend fun importImagesAsPdf(images: List<ByteArray>, suggestedName: String): ImportOutcome = nextOutcome()

    override suspend fun resolveDuplicate(docId: String, extraRelPath: String, keepCanonical: Boolean) {}
    override suspend fun applyRelink(oldDocId: String, newDocId: String) {}
    override suspend fun purgeTrash(olderThanDays: Int) {}
    override suspend fun reOcr(docId: String) {
        failReOcr?.invoke()
        reOcrIds += docId
    }

    val splits = mutableListOf<Pair<String, List<IntRange>>>()
    var splitOutcome: SplitOutcome = SplitOutcome.Done(emptyList())
    override suspend fun splitPdf(docId: String, segments: List<IntRange>): SplitOutcome {
        splits += docId to segments
        return splitOutcome
    }
    override fun start(scope: CoroutineScope) {}
    override fun close() {}
}

fun emptyScanReport(): ScanReport = ScanReport(
    added = 0,
    moved = 0,
    changed = 0,
    missingCount = 0,
    duplicates = emptyList(),
    conflictFiles = emptyList(),
    resurrected = emptyList(),
    relinkCandidates = emptyList(),
    hashedCount = 0,
    durationMillis = 0L,
)

/** Settings fake backed by [MutableStateFlow]s; setters both record and update the flow. */
class FakeSettingsRepository(
    initialDefaultView: ViewType? = ViewType.LIST,
) : SettingsRepository {
    val libraryLocationFlow = MutableStateFlow<String?>(null)
    val ocrLanguageFlow = MutableStateFlow("eng")
    val trashRetentionDaysFlow = MutableStateFlow(30)
    val defaultViewTypeFlow = MutableStateFlow(initialDefaultView)
    val librarySortFlow = MutableStateFlow(LibrarySort())
    val gridColumnWidthFlow = MutableStateFlow(160)
    val landscapeListColumnsFlow = MutableStateFlow(false)
    val installationIdFlow = MutableStateFlow("test-installation")

    val setDefaultViews = mutableListOf<ViewType>()
    val setSorts = mutableListOf<LibrarySort>()
    val setOcrLanguages = mutableListOf<String>()
    val setRetentionDays = mutableListOf<Int>()
    val setGridWidths = mutableListOf<Int>()
    val setLandscapeFlags = mutableListOf<Boolean>()

    override val libraryLocation: Flow<String?> get() = libraryLocationFlow
    override val installationId: Flow<String> get() = installationIdFlow
    override suspend fun setLibraryLocation(location: String?) { libraryLocationFlow.value = location }
    override val ocrLanguage: Flow<String> get() = ocrLanguageFlow
    override suspend fun setOcrLanguage(lang: String) { setOcrLanguages += lang; ocrLanguageFlow.value = lang }
    override val trashRetentionDays: Flow<Int> get() = trashRetentionDaysFlow
    override suspend fun setTrashRetentionDays(days: Int) { setRetentionDays += days; trashRetentionDaysFlow.value = days }
    override val defaultViewType: Flow<ViewType> get() = defaultViewTypeFlow.filterNotNull()
    fun emitDefaultView(viewType: ViewType) { defaultViewTypeFlow.value = viewType }
    override suspend fun setDefaultViewType(v: ViewType) { setDefaultViews += v; defaultViewTypeFlow.value = v }
    override val librarySort: Flow<LibrarySort> get() = librarySortFlow
    override suspend fun setLibrarySort(sort: LibrarySort) { setSorts += sort; librarySortFlow.value = sort }
    override val gridColumnWidth: Flow<Int> get() = gridColumnWidthFlow
    override suspend fun setGridColumnWidth(dp: Int) { setGridWidths += dp; gridColumnWidthFlow.value = dp }
    override val landscapeListColumns: Flow<Boolean> get() = landscapeListColumnsFlow
    override suspend fun setLandscapeListColumns(enabled: Boolean) { setLandscapeFlags += enabled; landscapeListColumnsFlow.value = enabled }

    val watchedFolderFlow = MutableStateFlow<String?>(null)
    val watchedFolderDeleteFlow = MutableStateFlow(false)
    val setWatchedFolders = mutableListOf<String?>()
    val setWatchedDeleteFlags = mutableListOf<Boolean>()

    override val watchedFolder: Flow<String?> get() = watchedFolderFlow
    override suspend fun setWatchedFolder(location: String?) {
        setWatchedFolders += location
        watchedFolderFlow.value = location
    }
    override val watchedFolderDeleteAfterImport: Flow<Boolean> get() = watchedFolderDeleteFlow
    override suspend fun setWatchedFolderDeleteAfterImport(enabled: Boolean) {
        setWatchedDeleteFlags += enabled
        watchedFolderDeleteFlow.value = enabled
    }
}
