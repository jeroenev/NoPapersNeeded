package dev.npn.app.ui.library

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.activeSearchToken
import dev.npn.app.ui.common.completeActiveSearchToken
import dev.npn.app.ui.common.textFieldValues
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.queue.QueueCounts
import dev.npn.core.app.DocLifecycle
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.FieldRegistry
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.IngestFacade
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.MetadataWriter
import dev.npn.core.app.OcrFacade
import dev.npn.core.app.OcrStatus
import dev.npn.core.app.ReviewConfigFacade
import dev.npn.core.app.SavedView
import dev.npn.core.app.SavedViewRepository
import dev.npn.core.app.ScanProgressUi
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.SortField
import dev.npn.core.app.TagInfo
import dev.npn.core.app.TagRepository
import dev.npn.core.app.ViewType
import dev.npn.core.app.WorkFacade
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Import result summary shown as a snackbar. */
data class ImportSummary(
    val imported: Int,
    val duplicate: Int,
    val failed: Int,
) {
    fun asUiText(): UiText = uiText(UiTextKey.IMPORT_SUMMARY, imported, duplicate, failed)
}

/** The filter and sort a result set was queried with. */
data class LibraryQuery(
    val filter: LibraryFilter,
    val sort: LibrarySort,
)

/**
 * A result set together with the [query] that produced it.
 *
 * The query travels with the items so the list can tell the two kinds of update apart: a new
 * query (different filter or sort) means these are different results and the list belongs back
 * at the top, while the same query re-emitting means the current results changed underneath the
 * user (import, tag edit, rescan) and the scroll position must stay put.
 */
data class DocumentResults(
    val query: LibraryQuery,
    val items: List<DocListItem>,
)

data class LibraryUiState(
    val filter: LibraryFilter = LibraryFilter(),
    val sort: LibrarySort = LibrarySort(),
    val viewType: ViewType = ViewType.LIST,
    val selectionMode: Boolean = false,
    val selected: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    val bannerDismissed: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Suppress("TooManyFunctions")
class LibraryViewModel(
    private val documentRepository: DocumentRepository,
    tagRepository: TagRepository,
    private val savedViewRepository: SavedViewRepository,
    private val meta: MetadataWriter,
    private val docLifecycle: DocLifecycle,
    private val ingest: IngestFacade,
    ocr: OcrFacade,
    work: WorkFacade,
    private val reviewConfig: ReviewConfigFacade,
    private val settings: SettingsRepository,
    fieldRegistry: FieldRegistry,
) : MessagesViewModel() {
    constructor(session: LibrarySession, settings: SettingsRepository) : this(
        documentRepository = session.documents,
        tagRepository = session.tags,
        savedViewRepository = session.savedViews,
        fieldRegistry = session.fieldRegistry,
        meta = session.meta,
        docLifecycle = session.docLifecycle,
        ingest = session.ingest,
        ocr = session.ocr,
        work = session.work,
        reviewConfig = session.reviewConfig,
        settings = settings,
    )

    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    // True once the user picks a view or applies a saved view this session. Guards against the
    // async default-view seed clobbering an explicit choice made before it arrives.
    private var viewOverridden = false

    // Same race guard for the persisted sort seed.
    private var sortOverridden = false

    /** Min grid cell width (dp), user-adjustable via settings; drives the adaptive column count. */
    val gridColumnWidth: StateFlow<Int> =
        settings.settings
            .map { it.gridColumnWidth }
            .distinctUntilChanged()
            .stateIn(viewModelScope, WhileSubscribedTimeout, 160)

    /** When true, the list view uses multiple columns while the window is landscape. */
    val landscapeListColumns: StateFlow<Boolean> =
        settings.settings
            .map { it.landscapeListColumns }
            .distinctUntilChanged()
            .stateIn(viewModelScope, WhileSubscribedTimeout, false)

    // Raw query text, debounced before it hits the filter. The search field reads this directly
    // (via [queryText]) so it reflects keystrokes immediately; the debounce only gates the filter.
    // Binding the field to the debounced filter value instead desyncs the Android IME and drops
    // input.
    private val queryInput = MutableStateFlow("")
    val queryText: StateFlow<String> = queryInput.asStateFlow()

    init {
        // Seed the initial view from the persisted default. Only applies the first value and only
        // if the user has not already switched the view this session.
        viewModelScope.launch {
            val saved = settings.settings.first().defaultViewType
            if (!viewOverridden) {
                _ui.update { it.copy(viewType = saved) }
            }
        }

        // Seed the sort from the last explicitly chosen one (separate launch so a slow view-type
        // seed cannot delay it, and vice versa).
        viewModelScope.launch {
            val saved = settings.settings.first().librarySort
            if (!sortOverridden) {
                _ui.update { it.copy(sort = saved) }
            }
        }

        viewModelScope.launch {
            queryInput
                .debounce(QUERY_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { q ->
                    _ui.update { it.copy(filter = it.filter.copy(query = q.ifBlank { null })) }
                }
        }
    }

    // null until the first query result arrives from Room, so the screen can show a loading
    // indicator instead of flashing the empty state on a slow initial load. Results keep carrying
    // the query they came from until the new one delivers, so a filter/sort change and the items
    // it produces reach the UI as one value (see [DocumentResults]).
    val documents: StateFlow<DocumentResults?> =
        combine(
            _ui.map { it.filter }.distinctUntilChanged(),
            _ui.map { it.sort }.distinctUntilChanged(),
        ) { f, s -> LibraryQuery(f, s) }
            .flatMapLatest { query ->
                documentRepository
                    .observeDocuments(query.filter, query.sort)
                    .map { DocumentResults(query, it) }
            }.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    /**
     * Autocomplete suggestions for the current query. Debounced separately from the filter
     * so suggestions are fast but do not fire on every keystroke.
     */
    val suggestions: StateFlow<List<String>> =
        queryText
            .debounce(SUGGESTION_DEBOUNCE_MS)
            .distinctUntilChanged()
            .flatMapLatest { q ->
                val prefix = activeSearchToken(q)
                if (prefix.length < MIN_SUGGESTION_LENGTH) {
                    flowOf(emptyList())
                } else {
                    flow { emit(documentRepository.searchSuggestions(prefix, SUGGESTION_LIMIT)) }
                }
            }.stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val tags: StateFlow<List<TagInfo>> =
        tagRepository
            .observeTags()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** The filter sheet's field rows: the registry plus each text field's values. */
    val fieldFilters: StateFlow<FieldFilterOptions> =
        combine(fieldRegistry.fields, fieldRegistry.textFieldValues()) { defs, values ->
            FieldFilterOptions(fields = defs, values = values)
        }.stateIn(viewModelScope, WhileSubscribedTimeout, FieldFilterOptions())

    /** Registry fields the sort menu offers: everything but prose, which has no useful order. */
    val sortableFields: StateFlow<List<FieldDef>> =
        fieldRegistry.fields
            .map { defs -> defs.filter { it.type != FieldType.LONG_TEXT } }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val reviewMode: StateFlow<ReviewMode> =
        reviewConfig.reviewMode
            .stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    val needsReviewCount: StateFlow<Int> =
        reviewConfig.reviewMode
            .flatMapLatest { mode ->
                when (mode) {
                    ReviewMode.UNTAGGED_ONLY -> documentRepository.observeUntaggedCount()
                    ReviewMode.INBOX_WORKFLOW -> documentRepository.observeUnreviewedOrUntaggedCount()
                }
            }.stateIn(viewModelScope, WhileSubscribedTimeout, 0)

    val savedViews: StateFlow<List<SavedView>> =
        savedViewRepository
            .observeSavedViews()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val scanProgress: StateFlow<ScanProgressUi?> =
        ingest.scanProgress
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    val lastScanReport: StateFlow<ScanReport?> =
        ingest.lastScanReport
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    val ocrStatus: StateFlow<OcrStatus> =
        ocr.ocrStatus
            .stateIn(viewModelScope, WhileSubscribedTimeout, OcrStatus(0, false))

    /**
     * Backlog sizes behind the library's progress banners. Only the counts: the banners open the
     * queue view, which subscribes to the entries itself.
     */
    val queueCounts: StateFlow<QueueCounts> =
        work.queue
            .map { QueueCounts.of(it) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, QueueCounts())

    fun onQueryChange(text: String) {
        queryInput.value = text
    }

    /**
     * Apply an autocomplete suggestion: updates the raw query text and the active filter
     * immediately (bypassing the filter debounce).
     */
    fun acceptSuggestion(text: String) {
        val completed = completeActiveSearchToken(queryInput.value, text)
        queryInput.value = completed
        _ui.update { it.copy(filter = it.filter.copy(query = completed.ifBlank { null })) }
    }

    fun setFilter(filter: LibraryFilter) {
        _ui.update { it.copy(filter = filter, bannerDismissed = false) }
        queryInput.value = filter.query ?: ""
    }

    fun clearFilter() {
        _ui.update { it.copy(filter = LibraryFilter()) }
        queryInput.value = ""
    }

    /**
     * Review queue preset. In UNTAGGED_ONLY mode this filters untagged documents; in INBOX_WORKFLOW
     * it filters documents that are unreviewed or untagged. Replaces any active filter.
     */
    fun applyReviewPreset() {
        val filter =
            when (reviewMode.value) {
                ReviewMode.UNTAGGED_ONLY -> LibraryFilter(untaggedOnly = true)
                ReviewMode.INBOX_WORKFLOW -> LibraryFilter(unreviewedOrUntagged = true)
            }
        _ui.update { it.copy(filter = filter) }
        queryInput.value = ""
    }

    fun setSort(
        field: SortField,
        ascending: Boolean,
    ) {
        sortOverridden = true
        val sort = LibrarySort(field, ascending)
        _ui.update { it.copy(sort = sort) }
        // Persist so the choice survives restart.
        viewModelScope.launch { settings.edit { it.copy(librarySort = sort) } }
    }

    fun setViewType(v: ViewType) {
        viewOverridden = true
        _ui.update { it.copy(viewType = v) }
        // Persist so the choice becomes the default and survives restart (also keeps the settings
        // screen in sync with the top-bar switcher).
        viewModelScope.launch { settings.edit { it.copy(defaultViewType = v) } }
    }

    fun applySavedView(view: SavedView) {
        viewOverridden = true
        // Guards the seed only; a saved view's sort is part of the view, not a new default.
        sortOverridden = true
        _ui.update { it.copy(filter = view.filter, sort = view.sort, viewType = view.viewType) }
        queryInput.value = view.filter.query ?: ""
    }

    /**
     * Persist the grid cell width (dp) chosen by direct manipulation (pinch / ctrl+scroll / zoom
     * buttons) on the grid. The grid previews changes locally and commits through here.
     */
    fun setGridColumnWidth(dp: Int) {
        viewModelScope.launch { settings.edit { it.copy(gridColumnWidth = dp) } }
    }

    private companion object {
        const val QUERY_DEBOUNCE_MS = 300L
        const val SUGGESTION_DEBOUNCE_MS = 100L
        const val SUGGESTION_LIMIT = 8
        const val MIN_SUGGESTION_LENGTH = 2
    }

    fun toggleSelection(docId: String) {
        _ui.update { cur ->
            val next = if (docId in cur.selected) cur.selected - docId else cur.selected + docId
            cur.copy(selected = next, selectionMode = next.isNotEmpty())
        }
    }

    fun startSelection(docId: String) {
        _ui.update { it.copy(selectionMode = true, selected = setOf(docId)) }
    }

    /** Enter selection mode with every visible document selected (desktop Ctrl+A / Select all). */
    fun selectAll(docIds: List<String>) {
        _ui.update { it.copy(selectionMode = true, selected = docIds.toSet()) }
    }

    fun clearSelection() {
        _ui.update { it.copy(selectionMode = false, selected = emptySet()) }
    }

    fun dismissBanner() {
        _ui.update { it.copy(bannerDismissed = true) }
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.update { it.copy(refreshing = true) }
            runCatching { ingest.rescan() }
            _ui.update { it.copy(refreshing = false) }
        }
    }

    // Tag/reviewed writes are a single batched write call (perDoc = false); trash is per-doc so
    // one failure does not abort the rest (perDoc = true). See [runOnSelection].
    fun bulkAddTag(tag: String) = runOnSelection(UiAction.ADD_TAG) { meta.addTag(it, tag) }

    fun bulkRemoveTag(tag: String) = runOnSelection(UiAction.REMOVE_TAG) { meta.removeTag(it, tag) }

    fun bulkSetReviewed(reviewed: Boolean) = runOnSelection(UiAction.UPDATE) { meta.setReviewed(it, reviewed) }

    fun bulkTrash() = runOnSelection(UiAction.TRASH, perDoc = true) { docLifecycle.trashDoc(it.single()) }

    /**
     * Run a bulk op over the current selection.
     *
     * - [perDoc] = false (default): [block] receives all selected ids as one batched call. On
     *   failure surface a message and keep the whole selection so the user can retry.
     * - [perDoc] = true: [block] is invoked once per id (as a singleton list) so each doc runs
     *   independently; only the ids that failed are kept selected for retry.
     *
     * On full success the selection is cleared. [action] names the op for the failure message.
     */
    private fun runOnSelection(
        action: UiAction,
        perDoc: Boolean = false,
        block: suspend (List<String>) -> Unit,
    ) {
        val ids = _ui.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (perDoc) {
                val failed = ids.filter { runCatching { block(listOf(it)) }.isFailure }
                if (failed.isEmpty()) {
                    clearSelection()
                } else {
                    _ui.update { it.copy(selected = failed.toSet(), selectionMode = true) }
                    message(uiText(UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS, action, failed.size, ids.size))
                }
            } else if (runCatching { block(ids) }.isSuccess) {
                clearSelection()
            } else {
                message(uiText(UiTextKey.COULD_NOT_DOCUMENTS, action, ids.size))
            }
        }
    }

    /** Import PDFs from external sources, tallying the per-source outcome into a message. */
    fun import(sources: List<Pair<ExternalSource, String>>) {
        viewModelScope.launch {
            var imported = 0
            var duplicate = 0
            var failed = 0
            sources.forEach { (src, name) ->
                val outcome = runCatching { ingest.importPdf(src, name) }.getOrNull()
                when (outcome) {
                    is ImportOutcome.Imported -> imported++
                    is ImportOutcome.Duplicate -> duplicate++
                    is ImportOutcome.Failed, null -> failed++
                }
            }
            message(ImportSummary(imported, duplicate, failed).asUiText())
        }
    }

    /** Import a scanned PDF (raw bytes from the platform scanner) through the staging import path. */
    fun importScannedPdf(bytes: ByteArray) {
        viewModelScope.launch { importScannedPdfNow(bytes) }
    }

    /** Launch a platform scan; cancellation stays quiet while an actual scanner failure is visible. */
    fun scan(scanToPdf: suspend () -> ByteArray?) {
        viewModelScope.launch {
            val bytes =
                try {
                    scanToPdf()
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    message(uiText(UiTextKey.SCAN_CAPTURE_FAILED))
                    return@launch
                }
            if (bytes != null) importScannedPdfNow(bytes)
        }
    }

    private suspend fun importScannedPdfNow(bytes: ByteArray) {
        val name = "scan-${randomId()}.pdf"
        val msg =
            runCatching { ingest.importPdfBytes(bytes, name) }.fold(
                onSuccess = { outcome ->
                    when (outcome) {
                        is ImportOutcome.Imported -> uiText(UiTextKey.SCAN_IMPORTED)
                        is ImportOutcome.Duplicate -> uiText(UiTextKey.SCAN_ALREADY_IN_LIBRARY)
                        is ImportOutcome.Failed -> uiText(UiTextKey.SCAN_IMPORT_FAILED, outcome.message)
                    }
                },
                onFailure = { uiText(UiTextKey.SCAN_IMPORT_FAILED, reasonOf(it)) },
            )
        message(msg)
    }

    // Names are trimmed here so the trimming rule lives in one place rather than in each dialog.

    /** Capture the live filter/sort/view UI state as a new [SavedView] named [name] and persist it. */
    fun saveCurrentView(name: String) =
        launchReporting(UiAction.UPDATE) { savedViewRepository.save(newSavedView(_ui.value, name.trim())) }

    /** Persist [view] under a new, whitespace-trimmed [name]. */
    fun renameSavedView(
        view: SavedView,
        name: String,
    ) = launchReporting(UiAction.UPDATE) { savedViewRepository.save(view.copy(name = name.trim())) }

    /** Flip the pinned state of [view] and persist it. */
    fun toggleSavedViewPin(view: SavedView) =
        launchReporting(UiAction.UPDATE) { savedViewRepository.save(view.copy(pinned = !view.pinned)) }

    /** Delete [view] by id. */
    fun deleteSavedView(view: SavedView) = launchReporting(UiAction.UPDATE) { savedViewRepository.delete(view.id) }

    /** Capture the given [ui] (filter/sort/view) as a new [SavedView] named [name]. */
    private fun newSavedView(
        ui: LibraryUiState,
        name: String,
    ): SavedView =
        SavedView(
            id = randomId(),
            name = name,
            filter = ui.filter,
            sort = ui.sort,
            viewType = ui.viewType,
        )
}

@OptIn(ExperimentalUuidApi::class)
private fun randomId(): String = Uuid.random().toString()
