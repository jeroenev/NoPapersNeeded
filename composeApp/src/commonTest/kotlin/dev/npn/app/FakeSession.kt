package dev.npn.app

import dev.npn.core.ai.AiChatEvent
import dev.npn.core.ai.AiChatTurn
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiFacade
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.AiTagging
import dev.npn.core.app.AutoMatchRuleRepository
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentHistoryRecord
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.OcrStatus
import dev.npn.core.app.OcrVersion
import dev.npn.core.app.SavedView
import dev.npn.core.app.SavedViewRepository
import dev.npn.core.app.ScanProgressUi
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.SettingsSnapshot
import dev.npn.core.app.SplitOutcome
import dev.npn.core.app.TagInfo
import dev.npn.core.app.TagRepository
import dev.npn.core.app.ViewType
import dev.npn.core.db.DocumentEntity
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
import dev.npn.core.model.ReviewMode
import dev.npn.core.rules.AutoMatchRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

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

    val count = MutableStateFlow(0)

    /** Mirrors the real implementation for the common case: count = documents.size. */
    override fun observeCount(filter: LibraryFilter): Flow<Int> =
        if (filter.query.isNullOrBlank()) {
            documents.map { it.size }
        } else {
            count
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
    val notesSets = mutableListOf<Pair<String, String?>>()
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
    val ocrEngineSets = mutableListOf<Pair<String, String?>>()

    /** If set, every batched op (addTag/removeTag/setReviewed) throws this. */
    var failBatchOps: (() -> Nothing)? = null

    /** addTag throws when any of these ids is in the call's docIds (per-doc partial-failure testing). */
    var failAddTagForDocs: Set<String> = emptySet()

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

    override suspend fun addTag(
        docIds: List<String>,
        tag: String,
    ) {
        failBatchOps?.invoke()
        if (docIds.any { it in failAddTagForDocs }) error("addTag failed for $docIds")
        addedTags += docIds to tag
    }

    override suspend fun removeTag(
        docIds: List<String>,
        tag: String,
    ) {
        failBatchOps?.invoke()
        removedTags += docIds to tag
    }

    override suspend fun setReviewed(
        docIds: List<String>,
        reviewed: Boolean,
    ) {
        failBatchOps?.invoke()
        reviewedSets += docIds to reviewed
    }

    override suspend fun setCorrespondent(
        docIds: List<String>,
        value: String?,
    ) {
        correspondentSets += docIds to value
    }

    override suspend fun setDocType(
        docIds: List<String>,
        value: String?,
    ) {
        docTypeSets += docIds to value
    }

    override suspend fun setDocDate(
        docId: String,
        isoDate: String?,
    ) {
        docDateSets += docId to isoDate
    }

    override suspend fun setTitle(
        docId: String,
        title: String?,
    ) {
        failSetTitle?.invoke()
        titleSets += docId to title
    }

    override suspend fun setNotes(
        docId: String,
        notes: String?,
    ) {
        notesSets += docId to notes
    }

    override suspend fun setField(
        docId: String,
        key: String,
        value: String?,
    ) {
        failSetField?.invoke()
        fieldSets += Triple(docId, key, value)
    }

    override suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    ) {
        ocrEngineSets += docId to engine
    }

    override suspend fun renameTag(
        from: String,
        to: String,
    ) {
        failTagAdmin?.invoke()
        renamedTags += from to to
    }

    override suspend fun mergeTag(
        from: String,
        to: String,
    ) {
        failTagAdmin?.invoke()
        mergedTags += from to to
    }

    override suspend fun deleteTag(name: String) {
        failTagAdmin?.invoke()
        deletedTags += name
    }

    override suspend fun setTagStyle(
        tag: String,
        color: String?,
        icon: String?,
    ) {
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

    override suspend fun deleteDocPermanently(docId: String) {
        deletedDocs += docId
    }

    override suspend fun renameFile(
        docId: String,
        newFileName: String,
    ) {
        renamedFiles += docId to newFileName
    }

    override suspend fun moveFile(
        docId: String,
        newDirRelPath: String,
    ) {
        movedFiles += docId to newDirRelPath
    }
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

    override suspend fun renderPage(
        docId: String,
        pageIndex: Int,
        maxDim: Int,
    ): ByteArray? = null

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

    /** Per-doc extracted text; a docId present here wins over [extractedTextValue]. */
    var extractedTextByDoc: Map<String, String?> = emptyMap()

    override suspend fun extractedText(docId: String): String? =
        if (docId in extractedTextByDoc) extractedTextByDoc[docId] else extractedTextValue
}

/**
 * AI fake: [askScripts] are event lists consumed in order (last one repeats); each backs one
 * [ask] flow. [askResponses] is a convenience: a plain answer string is turned into a
 * `TextDelta` + `Done` script. [suggestions] backs [suggestTags]. Failure injection via the
 * `fail*` hooks ([failAsk] throws when the returned flow is collected), call recording via
 * `asked`/[suggestTagsCalls]. [askDelayMillis] delays each emitted event so tests can observe
 * intermediate streaming state and cancellation.
 */
class FakeAiFacade : AiFacade {
    var configValue = AiConfig(enabled = true, baseUrl = "http://localhost:1", model = "test-model")
    var askScripts: List<List<AiChatEvent>> = emptyList()
    var askResponses: List<String> = emptyList()
    var askDelayMillis: Long = 0
    private var askIdx = 0
    val asked = mutableListOf<Pair<List<AiChatTurn>, String>>()
    var failAsk: (() -> Nothing)? = null

    /** When set, the ask flow throws after emitting this many events (mid-stream failure). */
    var failAskAfter: Int? = null
    var suggestions: List<TagSuggestion> = emptyList()

    /**
     * Per-doc suggestion scripts for the bulk tagging flow. When a docId has an entry here it wins
     * over [suggestions]; docs absent from the map fall back to [suggestions]. Lets tests drive
     * distinct results (including empty lists for the "no suggestions" grouping) per document.
     */
    var suggestionsByDoc: Map<String, List<TagSuggestion>> = emptyMap()

    /** docId-only view of [suggestTagsTriples] for assertions that only care about which docs. */
    val suggestTagsCalls = mutableListOf<String>()

    /** Every (docId, maxTags, maxNewTags) triple passed to [suggestTags], in dispatch order. */
    val suggestTagsTriples = mutableListOf<Triple<String, Int?, Int?>>()
    var failSuggestTags: (() -> Nothing)? = null

    /** suggestTags throws for these ids only (per-doc partial-failure testing). */
    var failSuggestTagsForDocs: Set<String> = emptySet()

    /** Optional per-call delay so tests can observe progress and cancel mid-run. */
    var suggestDelayMillis: Long = 0

    /** Per-doc delay override (wins over [suggestDelayMillis]); lets tests skew completion order. */
    var suggestDelayByDoc: Map<String, Long> = emptyMap()

    override suspend fun config(): AiConfig = configValue

    override fun ask(
        history: List<AiChatTurn>,
        question: String,
    ): Flow<AiChatEvent> {
        val script = scriptFor(askIdx)
        askIdx++
        asked += history to question
        return flow {
            failAsk?.invoke()
            var emitted = 0
            for (event in script) {
                if (failAskAfter == emitted) error("stream interrupted")
                if (askDelayMillis > 0) kotlinx.coroutines.delay(askDelayMillis)
                emit(event)
                emitted++
            }
        }
    }

    private fun scriptFor(index: Int): List<AiChatEvent> {
        if (askScripts.isNotEmpty()) return askScripts.getOrElse(index) { askScripts.last() }
        val text = askResponses.getOrElse(index) { askResponses.lastOrNull() ?: "answer" }
        return listOf(AiChatEvent.TextDelta(text), AiChatEvent.Done(text))
    }

    override suspend fun suggestTags(
        docId: String,
        maxTags: Int?,
        maxNewTags: Int?,
    ): List<TagSuggestion> {
        // Record at entry (before the delay) so the call list reflects dispatch order
        // deterministically even when calls run concurrently.
        suggestTagsCalls += docId
        suggestTagsTriples += Triple(docId, maxTags, maxNewTags)
        val delay = suggestDelayByDoc[docId] ?: suggestDelayMillis
        if (delay > 0) kotlinx.coroutines.delay(delay)
        failSuggestTags?.invoke()
        if (docId in failSuggestTagsForDocs) error("suggest failed for $docId")
        return suggestionsByDoc[docId] ?: suggestions
    }
}

class FakeLibrarySession : LibrarySession {
    override val documents = FakeDocumentRepository()
    override val tags = FakeTagRepository()
    override val savedViews = FakeSavedViewRepository()
    override val rules = FakeAutoMatchRuleRepository()
    override val meta = FakeMetadataWriter()
    override val content = FakeDocContentFacade()
    override val ai = FakeAiFacade()
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

    /** Backing list returned by [ocrVersions]; tests mutate it to drive the picker. */
    var ocrVersionsList: List<OcrVersion> = emptyList()
    val ocrEngineSets = mutableListOf<Pair<String, String?>>()
    var failSetOcrEngine: (() -> Nothing)? = null

    override suspend fun setReviewMode(mode: ReviewMode) {
        reviewModeSets += mode
        reviewModeFlow.value = mode
    }

    override suspend fun markReviewedForMigration(onlyTagged: Boolean) {
        migrations += onlyTagged
    }

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

    override suspend fun importPdf(
        source: ExternalSource,
        suggestedName: String,
    ): ImportOutcome = nextOutcome()

    override suspend fun importPdfBytes(
        bytes: ByteArray,
        suggestedName: String,
    ): ImportOutcome = nextOutcome()

    override suspend fun importImagesAsPdf(
        images: List<ByteArray>,
        suggestedName: String,
    ): ImportOutcome = nextOutcome()

    override suspend fun resolveDuplicate(
        docId: String,
        extraRelPath: String,
        keepCanonical: Boolean,
    ) {}

    override suspend fun applyRelink(
        oldDocId: String,
        newDocId: String,
    ) {}

    override suspend fun purgeTrash(olderThanDays: Int) {}

    override suspend fun reOcr(docId: String) {
        failReOcr?.invoke()
        reOcrIds += docId
    }

    override suspend fun ocrVersions(docId: String): List<OcrVersion> = ocrVersionsList

    override suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    ) {
        failSetOcrEngine?.invoke()
        ocrEngineSets += docId to engine
    }

    val splits = mutableListOf<Pair<String, List<IntRange>>>()
    var splitOutcome: SplitOutcome = SplitOutcome.Done(emptyList())

    override suspend fun splitPdf(
        docId: String,
        segments: List<IntRange>,
    ): SplitOutcome {
        splits += docId to segments
        return splitOutcome
    }

    override fun start(scope: CoroutineScope) {}

    override suspend fun close() {}
}

fun emptyScanReport(): ScanReport =
    ScanReport(
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

/** Settings fake backed by one snapshot flow; setters both record and update the snapshot. */
class FakeSettingsRepository(
    initialDefaultView: ViewType? = ViewType.LIST,
) : SettingsRepository {
    val settingsFlow =
        MutableStateFlow(
            initialDefaultView?.let {
                SettingsSnapshot(installationId = "test-installation", defaultViewType = it)
            },
        )

    override val settings: Flow<SettingsSnapshot> = settingsFlow.filterNotNull()

    fun updateSettings(transform: (SettingsSnapshot) -> SettingsSnapshot) {
        settingsFlow.value = transform(settingsFlow.value ?: defaultSnapshot())
    }

    val setDefaultViews = mutableListOf<ViewType>()
    val setSorts = mutableListOf<LibrarySort>()
    val setOcrLanguages = mutableListOf<String>()
    val setRetentionDays = mutableListOf<Int>()
    val setGridWidths = mutableListOf<Int>()
    val setLandscapeFlags = mutableListOf<Boolean>()
    val setStoragePathTemplates = mutableListOf<String?>()
    val setStoragePathEnabledFlags = mutableListOf<Boolean>()
    val setAppLanguages = mutableListOf<String?>()

    override suspend fun setLibraryLocation(location: String?) = updateSettings { it.copy(libraryLocation = location) }

    override suspend fun setAppLanguage(lang: String?) {
        setAppLanguages += lang
        updateSettings { it.copy(appLanguage = lang) }
    }

    override suspend fun setOcrLanguage(lang: String) {
        setOcrLanguages += lang
        updateSettings { it.copy(ocrLanguage = lang) }
    }

    override suspend fun setTrashRetentionDays(days: Int) {
        setRetentionDays += days
        updateSettings { it.copy(trashRetentionDays = days) }
    }

    fun emitDefaultView(viewType: ViewType) = updateSettings { it.copy(defaultViewType = viewType) }

    override suspend fun setDefaultViewType(v: ViewType) {
        setDefaultViews += v
        updateSettings { it.copy(defaultViewType = v) }
    }

    override suspend fun setLibrarySort(sort: LibrarySort) {
        setSorts += sort
        updateSettings { it.copy(librarySort = sort) }
    }

    override suspend fun setGridColumnWidth(dp: Int) {
        setGridWidths += dp
        updateSettings { it.copy(gridColumnWidth = dp) }
    }

    override suspend fun setLandscapeListColumns(enabled: Boolean) {
        setLandscapeFlags += enabled
        updateSettings { it.copy(landscapeListColumns = enabled) }
    }

    override suspend fun setStoragePathTemplate(template: String?) {
        setStoragePathTemplates += template
        updateSettings { it.copy(storagePathTemplate = template) }
    }

    override suspend fun setStoragePathEnabled(enabled: Boolean) {
        setStoragePathEnabledFlags += enabled
        updateSettings { it.copy(storagePathEnabled = enabled) }
    }

    override suspend fun setAutoMatchRulesJson(json: String) = updateSettings { it.copy(autoMatchRulesJson = json) }

    val setAiMaxTagsPerDocValues = mutableListOf<Int>()
    val setAiMaxNewTagsPerDocValues = mutableListOf<Int>()

    override suspend fun setAiEnabled(enabled: Boolean) = updateSettings { it.copy(aiEnabled = enabled) }

    override suspend fun setAiBaseUrl(url: String) = updateSettings { it.copy(aiBaseUrl = url) }

    override suspend fun setAiApiKey(key: String) = updateSettings { it.copy(aiApiKey = key) }

    override suspend fun setAiModel(model: String) = updateSettings { it.copy(aiModel = model) }

    override suspend fun setAiMaxTagsPerDoc(value: Int) {
        setAiMaxTagsPerDocValues += value
        updateSettings { it.copy(aiMaxTagsPerDoc = value.coerceIn(AiTagging.RANGE)) }
    }

    override suspend fun setAiMaxNewTagsPerDoc(value: Int) {
        setAiMaxNewTagsPerDocValues += value
        updateSettings { it.copy(aiMaxNewTagsPerDoc = value.coerceIn(AiTagging.NEW_TAGS_RANGE)) }
    }

    val setWatchedFolders = mutableListOf<String?>()
    val setWatchedDeleteFlags = mutableListOf<Boolean>()

    override suspend fun setWatchedFolder(location: String?) {
        setWatchedFolders += location
        updateSettings { it.copy(watchedFolder = location) }
    }

    override suspend fun setWatchedFolderDeleteAfterImport(enabled: Boolean) {
        setWatchedDeleteFlags += enabled
        updateSettings { it.copy(watchedFolderDeleteAfterImport = enabled) }
    }

    private fun defaultSnapshot() =
        SettingsSnapshot(
            installationId = "test-installation",
            defaultViewType = ViewType.LIST,
        )
}
