package dev.npn.app.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.app.DocListItem
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SettingsRepository
import dev.npn.core.app.OcrStatus
import dev.npn.core.app.SavedView
import dev.npn.core.app.ScanProgressUi
import dev.npn.core.app.SortField
import dev.npn.core.app.TagInfo
import dev.npn.core.app.ViewType
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.ScanReport
import dev.npn.core.model.ReviewMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Import result summary shown as a snackbar. */
data class ImportSummary(val imported: Int, val duplicate: Int, val failed: Int) {
    val message: String
        get() = buildString {
            append("$imported imported")
            if (duplicate > 0) append(", $duplicate duplicate")
            if (failed > 0) append(", $failed failed")
        }
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
class LibraryViewModel(
    val session: LibrarySession,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _ui = MutableStateFlow(LibraryUiState())
    val ui: StateFlow<LibraryUiState> = _ui.asStateFlow()

    // True once the user picks a view or applies a saved view this session. Guards against the
    // async default-view seed clobbering an explicit choice made before it arrives.
    private var viewOverridden = false

    /** Min grid cell width (dp), user-adjustable via settings; drives the adaptive column count. */
    val gridColumnWidth: StateFlow<Int> =
        settings.gridColumnWidth
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 160)

    /** When true, the list view uses multiple columns while the window is landscape. */
    val landscapeListColumns: StateFlow<Boolean> =
        settings.landscapeListColumns
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

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
            val saved = settings.defaultViewType.first()
            if (!viewOverridden) {
                _ui.value = _ui.value.copy(viewType = saved)
            }
        }

        viewModelScope.launch {
            queryInput
                .debounce(300)
                .distinctUntilChanged()
                .collect { q ->
                    _ui.value = _ui.value.copy(
                        filter = _ui.value.filter.copy(query = q.ifBlank { null }),
                    )
                }
        }
    }

    val documents: StateFlow<List<DocListItem>> =
        combine(
            _ui.map { it.filter }.distinctUntilChanged(),
            _ui.map { it.sort }.distinctUntilChanged(),
        ) { f, s -> f to s }
            .flatMapLatest { (f, s) -> session.documents.observeDocuments(f, s) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val tags: StateFlow<List<TagInfo>> =
        session.tags.observeTags()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val correspondents: StateFlow<List<String>> =
        session.documents.observeCorrespondents()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val docTypes: StateFlow<List<String>> =
        session.documents.observeDocTypes()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val reviewMode: StateFlow<ReviewMode> =
        session.reviewMode
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ReviewMode.DEFAULT)

    val needsReviewCount: StateFlow<Int> =
        session.reviewMode.flatMapLatest { mode ->
            when (mode) {
                ReviewMode.UNTAGGED_ONLY -> session.documents.observeUntaggedCount()
                ReviewMode.INBOX_WORKFLOW -> session.documents.observeUnreviewedOrUntaggedCount()
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val savedViews: StateFlow<List<SavedView>> =
        session.savedViews.observeSavedViews()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val scanProgress: StateFlow<ScanProgressUi?> =
        session.scanProgress
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val lastScanReport: StateFlow<ScanReport?> =
        session.lastScanReport
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val ocrStatus: StateFlow<OcrStatus> =
        session.ocrStatus
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OcrStatus(0, false))

    // ---- filter/sort/view controls ----

    fun onQueryChange(text: String) {
        queryInput.value = text
    }

    fun setFilter(filter: LibraryFilter) {
        _ui.value = _ui.value.copy(filter = filter, bannerDismissed = false)
        queryInput.value = filter.query ?: ""
    }

    fun updateFilter(block: (LibraryFilter) -> LibraryFilter) {
        _ui.value = _ui.value.copy(filter = block(_ui.value.filter))
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
        val filter = when (reviewMode.value) {
            ReviewMode.UNTAGGED_ONLY -> LibraryFilter(untaggedOnly = true)
            ReviewMode.INBOX_WORKFLOW -> LibraryFilter(unreviewedOrUntagged = true)
        }
        _ui.value = _ui.value.copy(filter = filter)
        queryInput.value = ""
    }

    fun setSort(field: SortField, ascending: Boolean) {
        _ui.value = _ui.value.copy(sort = LibrarySort(field, ascending))
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
        _ui.value = _ui.value.copy(filter = view.filter, sort = view.sort, viewType = view.viewType)
        queryInput.value = view.filter.query ?: ""
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
            runCatching { session.rescan() }
            _ui.value = _ui.value.copy(refreshing = false)
        }
    }

    // ---- bulk metadata ops ----

    fun bulkAddTag(tag: String) = withSelection { session.meta.addTag(it, tag) }
    fun bulkRemoveTag(tag: String) = withSelection { session.meta.removeTag(it, tag) }
    fun bulkSetReviewed(reviewed: Boolean) = withSelection { session.meta.setReviewed(it, reviewed) }
    fun bulkTrash() = withSelection { ids -> ids.forEach { session.meta.trashDoc(it) } }

    private fun withSelection(block: suspend (List<String>) -> Unit) {
        val ids = _ui.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            runCatching { block(ids) }
            clearSelection()
        }
    }

    suspend fun allTagNames(): List<String> = session.tags.allTagNames()

    // ---- saved views ----

    fun saveCurrentView(name: String) {
        val cur = _ui.value
        viewModelScope.launch {
            session.savedViews.save(
                SavedView(
                    id = randomId(),
                    name = name,
                    filter = cur.filter,
                    sort = cur.sort,
                    viewType = cur.viewType,
                ),
            )
        }
    }

    // ---- import ----

    fun import(sources: List<Pair<ExternalSource, String>>, onDone: (ImportSummary) -> Unit) {
        viewModelScope.launch {
            var imported = 0
            var duplicate = 0
            var failed = 0
            sources.forEach { (src, name) ->
                when (runCatching { session.importPdf(src, name) }.getOrElse { ImportOutcome.Failed(it.message ?: "error") }) {
                    is ImportOutcome.Imported -> imported++
                    is ImportOutcome.Duplicate -> duplicate++
                    is ImportOutcome.Failed -> failed++
                }
            }
            onDone(ImportSummary(imported, duplicate, failed))
        }
    }

    /** Import a scanned PDF (raw bytes from the platform scanner) through the staging import path. */
    fun importScannedPdf(bytes: ByteArray, onDone: (String) -> Unit) {
        viewModelScope.launch {
            val name = "scan-${randomId()}.pdf"
            val msg = when (
                val r = runCatching { session.importPdfBytes(bytes, name) }
                    .getOrElse { ImportOutcome.Failed(it.message ?: "error") }
            ) {
                is ImportOutcome.Imported -> "Scan imported"
                is ImportOutcome.Duplicate -> "Scan already in library"
                is ImportOutcome.Failed -> "Scan import failed: ${r.message}"
            }
            onDone(msg)
        }
    }

    // ---- saved view management ----

    fun renameSavedView(view: SavedView, name: String) {
        viewModelScope.launch { session.savedViews.save(view.copy(name = name.trim())) }
    }

    fun deleteSavedView(id: String) {
        viewModelScope.launch { session.savedViews.delete(id) }
    }

    fun togglePinSavedView(view: SavedView) {
        viewModelScope.launch { session.savedViews.save(view.copy(pinned = !view.pinned)) }
    }
}

private fun randomId(): String {
    val chars = "abcdefghijklmnopqrstuvwxyz0123456789"
    return buildString { repeat(16) { append(chars.random()) } }
}
