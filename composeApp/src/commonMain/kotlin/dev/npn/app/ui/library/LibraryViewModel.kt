package dev.npn.app.ui.library

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.activeSearchToken
import dev.npn.app.ui.common.completeActiveSearchToken
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.DocLifecycle
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentRepository
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
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
import dev.npn.core.model.ReviewMode
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
    savedViewRepository: SavedViewRepository,
    private val meta: MetadataWriter,
    private val docLifecycle: DocLifecycle,
    private val ingest: IngestFacade,
    ocr: OcrFacade,
    private val reviewConfig: ReviewConfigFacade,
    private val settings: SettingsRepository,
) : MessagesViewModel() {
    constructor(session: LibrarySession, settings: SettingsRepository) : this(
        documentRepository = session.documents,
        tagRepository = session.tags,
        savedViewRepository = session.savedViews,
        meta = session.meta,
        docLifecycle = session.docLifecycle,
        ingest = session.ingest,
        ocr = session.ocr,
        reviewConfig = session.reviewConfig,
        settings = settings,
    )

    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    // One-shot user-facing messages (partial bulk-op failures) are surfaced via the inherited
    // [MessagesViewModel.message] API and collected by the screen as snackbars.

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
                _ui.value = _ui.value.copy(viewType = saved)
            }
        }

        // Seed the sort from the last explicitly chosen one (separate launch so a slow view-type
        // seed cannot delay it, and vice versa).
        viewModelScope.launch {
            val saved = settings.settings.first().librarySort
            if (!sortOverridden) {
                _ui.value = _ui.value.copy(sort = saved)
            }
        }

        viewModelScope.launch {
            queryInput
                .debounce(QUERY_DEBOUNCE_MS)
                .distinctUntilChanged()
                .collect { q ->
                    _ui.value =
                        _ui.value.copy(
                            filter = _ui.value.filter.copy(query = q.ifBlank { null }),
                        )
                }
        }
    }

    // null until the first query result arrives from Room, so the screen can show a loading
    // indicator instead of flashing the empty state on a slow initial load.
    val documents: StateFlow<List<DocListItem>?> =
        combine(
            _ui.map { it.filter }.distinctUntilChanged(),
            _ui.map { it.sort }.distinctUntilChanged(),
        ) { f, s -> f to s }
            .flatMapLatest { (f, s) -> documentRepository.observeDocuments(f, s) }
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

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

    val correspondents: StateFlow<List<String>> =
        documentRepository
            .observeCorrespondents()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val docTypes: StateFlow<List<String>> =
        documentRepository
            .observeDocTypes()
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

    // ---- filter/sort/view controls ----

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
        _ui.value = _ui.value.copy(filter = _ui.value.filter.copy(query = completed.ifBlank { null }))
    }

    fun setFilter(filter: LibraryFilter) {
        _ui.value = _ui.value.copy(filter = filter, bannerDismissed = false)
        queryInput.value = filter.query ?: ""
    }

    fun clearFilter() {
        _ui.value = _ui.value.copy(filter = LibraryFilter())
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
        _ui.value = _ui.value.copy(filter = filter)
        queryInput.value = ""
    }

    fun setSort(
        field: SortField,
        ascending: Boolean,
    ) {
        sortOverridden = true
        val sort = LibrarySort(field, ascending)
        _ui.value = _ui.value.copy(sort = sort)
        // Persist so the choice survives restart.
        viewModelScope.launch { settings.setLibrarySort(sort) }
    }

    fun setViewType(v: ViewType) {
        viewOverridden = true
        _ui.value = _ui.value.copy(viewType = v)
        // Persist so the choice becomes the default and survives restart (also keeps the settings
        // screen in sync with the top-bar switcher).
        viewModelScope.launch { settings.setDefaultViewType(v) }
    }

    fun applySavedView(view: SavedView) {
        viewOverridden = true
        // Guards the seed only; a saved view's sort is part of the view, not a new default.
        sortOverridden = true
        _ui.value = _ui.value.copy(filter = view.filter, sort = view.sort, viewType = view.viewType)
        queryInput.value = view.filter.query ?: ""
    }

    /**
     * Persist the grid cell width (dp) chosen by direct manipulation (pinch / ctrl+scroll / zoom
     * buttons) on the grid. The grid previews changes locally and commits through here.
     */
    fun setGridColumnWidth(dp: Int) {
        viewModelScope.launch { settings.setGridColumnWidth(dp) }
    }

    private companion object {
        const val QUERY_DEBOUNCE_MS = 300L
        const val SUGGESTION_DEBOUNCE_MS = 100L
        const val SUGGESTION_LIMIT = 8
        const val MIN_SUGGESTION_LENGTH = 2
    }

    // ---- selection ----

    fun toggleSelection(docId: String) {
        val cur = _ui.value
        val next = if (docId in cur.selected) cur.selected - docId else cur.selected + docId
        _ui.value = cur.copy(selected = next, selectionMode = next.isNotEmpty())
    }

    fun startSelection(docId: String) {
        _ui.value = _ui.value.copy(selectionMode = true, selected = setOf(docId))
    }

    /** Enter selection mode with every visible document selected (desktop Ctrl+A / Select all). */
    fun selectAll(docIds: List<String>) {
        _ui.value = _ui.value.copy(selectionMode = true, selected = docIds.toSet())
    }

    fun clearSelection() {
        _ui.value = _ui.value.copy(selectionMode = false, selected = emptySet())
    }

    fun dismissBanner() {
        _ui.value = _ui.value.copy(bannerDismissed = true)
    }

    // ---- scan / refresh ----

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(refreshing = true)
            runCatching { ingest.rescan() }
            _ui.value = _ui.value.copy(refreshing = false)
        }
    }

    // ---- bulk metadata ops ----

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
                    _ui.value = _ui.value.copy(selected = failed.toSet(), selectionMode = true)
                    message(uiText(UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS, action, failed.size, ids.size))
                }
            } else if (runCatching { block(ids) }.isSuccess) {
                clearSelection()
            } else {
                message(uiText(UiTextKey.COULD_NOT_DOCUMENTS, action, ids.size))
            }
        }
    }

    // ---- import ----

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
        viewModelScope.launch {
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
    }

    // Saved-view create/rename/pin/delete are thin, single-line pass-throughs to
    // [LibrarySession.savedViews] with no view-model state of their own, so they run from the
    // drawer's own coroutine scope (see LibraryScreen) rather than adding a wrapper method each
    // here. Building a new view from the live UI state is a pure helper: see [newSavedView].
}

@OptIn(ExperimentalUuidApi::class)
private fun randomId(): String = Uuid.random().toString()

/** Capture [ui] (filter/sort/view) as a new [SavedView] named [name]. */
internal fun newSavedView(
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
