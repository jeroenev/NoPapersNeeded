package dev.npn.app.ui.library

import androidx.lifecycle.viewModelScope
import dev.npn.app.platform.ScanResult
import dev.npn.app.ui.common.BulkFailure
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.activeSearchToken
import dev.npn.app.ui.common.asUiText
import dev.npn.app.ui.common.colorsByName
import dev.npn.app.ui.common.completeActiveSearchToken
import dev.npn.app.ui.common.text
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.DocListItem
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.OcrStatus
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.app.TagInfo
import dev.npn.core.app.TrashReceipt
import dev.npn.core.app.ViewType
import dev.npn.core.app.WorkStage
import dev.npn.core.app.importAll
import dev.npn.core.app.reviewQueueFilter
import dev.npn.core.app.textFieldValues
import dev.npn.core.fs.ExternalSource
import dev.npn.core.index.IndexProgress
import dev.npn.core.index.ScanReport
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.settings.aiConfigured
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
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
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_restore_documents_failed
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.common_scan_already_in_library
import no_papers_needed.composeapp.generated.resources.common_scan_capture_failed
import no_papers_needed.composeapp.generated.resources.common_scan_import_failed
import no_papers_needed.composeapp.generated.resources.common_scan_imported
import no_papers_needed.composeapp.generated.resources.detail_add_tag_failed
import no_papers_needed.composeapp.generated.resources.detail_remove_tag_failed
import no_papers_needed.composeapp.generated.resources.detail_trash_failed
import no_papers_needed.composeapp.generated.resources.library_delete_view_failed
import no_papers_needed.composeapp.generated.resources.library_import_failed
import no_papers_needed.composeapp.generated.resources.library_moved_to_trash
import no_papers_needed.composeapp.generated.resources.library_refresh_failed
import no_papers_needed.composeapp.generated.resources.library_review_failed
import no_papers_needed.composeapp.generated.resources.library_save_view_failed
import no_papers_needed.composeapp.generated.resources.library_trash_failed
import no_papers_needed.composeapp.generated.resources.library_undo
import org.jetbrains.compose.resources.StringResource
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

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
    val selected: Set<String> = emptySet(),
    val refreshing: Boolean = false,
    /** The scan report whose issues banner the user dismissed; a later report brings it back. */
    val dismissedScanReport: ScanReport? = null,
) {
    /** Selection mode lasts exactly as long as something is selected. */
    val selectionMode: Boolean get() = selected.isNotEmpty()

    /** Whether the user dismissed the issues banner of [report], and not only that of an earlier scan. */
    fun scanIssuesDismissed(report: ScanReport?): Boolean = report != null && report == dismissedScanReport
}

/** The grid's minimum cell width (dp) until the settings have loaded. */
internal const val DEFAULT_GRID_COLUMN_WIDTH = 160

/**
 * What the library list asks of its viewmodel, so the list composables take this and never the
 * viewmodel itself. Implemented by [LibraryViewModel]; each member's contract is documented there.
 */
interface LibraryActions {
    fun onQueryChange(text: String)

    fun acceptSuggestion(text: String)

    fun setFilter(filter: LibraryFilter)

    fun clearFilter()

    fun applyReviewPreset()

    fun setSort(
        field: SortField,
        ascending: Boolean,
    )

    fun setViewType(v: ViewType)

    fun setGridColumnWidth(dp: Int)

    fun toggleSelection(docId: String)

    fun startSelection(docId: String)

    fun selectAll(docIds: List<String>)

    fun clearSelection()

    fun dismissBanner()

    fun refresh()

    fun bulkSetReviewed(reviewed: Boolean)

    fun import(sources: List<Pair<ExternalSource, String>>)

    fun importScan(result: ScanResult)

    suspend fun tagsInSelection(): List<String>

    fun applySavedView(view: SavedView)

    fun toggleSavedViewPin(view: SavedView)

    fun deleteSavedView(view: SavedView)
}

@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
class LibraryViewModel(
    session: LibrarySession,
    private val settings: SettingsRepository,
) : MessagesViewModel(),
    LibraryActions {
    private val documentRepository = session.documents
    private val savedViewRepository = session.savedViews
    private val meta = session.meta
    private val docLifecycle = session.docLifecycle
    private val ingest = session.ingest
    private val reviewConfig = session.reviewConfig
    override val log = session.log

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
            .stateIn(viewModelScope, WhileSubscribedTimeout, DEFAULT_GRID_COLUMN_WIDTH)

    /** Whether AI is configured, which offers AI edit on a selection; follows Settings. */
    val aiConfigured: StateFlow<Boolean> =
        settings.aiConfigured().stateIn(viewModelScope, WhileSubscribedTimeout, false)

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
        // Seed the view from the persisted default and the sort from the last explicitly chosen
        // one. Only the first value applies, and each only if the user has not already chosen this
        // session.
        viewModelScope.launch {
            val saved = settings.settings.first()
            _ui.update {
                it.copy(
                    viewType = if (viewOverridden) it.viewType else saved.defaultViewType,
                    sort = if (sortOverridden) it.sort else saved.librarySort,
                )
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
        session.tags
            .observeTags()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    /** Each tag's colour by name, for the tag chips on the documents. */
    val tagColors: StateFlow<Map<String, String?>> =
        tags
            .map { it.colorsByName() }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyMap())

    /** The filter sheet's field rows: the registry plus each text field's values. */
    val fieldFilters: StateFlow<FieldFilterOptions> =
        combine(session.fieldRegistry.fields, session.fieldRegistry.textFieldValues()) { defs, values ->
            FieldFilterOptions(fields = defs, values = values)
        }.stateIn(viewModelScope, WhileSubscribedTimeout, FieldFilterOptions())

    /** Registry fields the sort menu offers: everything but prose, which has no useful order. */
    val sortableFields: StateFlow<List<FieldDef>> =
        session.fieldRegistry.fields
            .map { defs -> defs.filter { it.type.singleLine } }
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val reviewMode: StateFlow<ReviewMode> =
        reviewConfig.reviewMode
            .stateIn(viewModelScope, WhileSubscribedTimeout, ReviewMode.DEFAULT)

    val needsReviewCount: StateFlow<Int> =
        reviewConfig.reviewMode
            .flatMapLatest(documentRepository::observeReviewQueueCount)
            .stateIn(viewModelScope, WhileSubscribedTimeout, 0)

    val savedViews: StateFlow<List<SavedView>> =
        savedViewRepository
            .observeSavedViews()
            .stateIn(viewModelScope, WhileSubscribedTimeout, emptyList())

    val scanProgress: StateFlow<IndexProgress?> =
        ingest.scanProgress
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    val lastScanReport: StateFlow<ScanReport?> =
        ingest.lastScanReport
            .stateIn(viewModelScope, WhileSubscribedTimeout, null)

    val ocrStatus: StateFlow<OcrStatus> =
        session.ocr.ocrStatus
            .stateIn(viewModelScope, WhileSubscribedTimeout, OcrStatus(0, false))

    /**
     * Documents still waiting for AI enrichment, behind the library's progress banner. Only the
     * count: the banner opens the queue view, which subscribes to the entries itself.
     */
    val enrichmentPending: StateFlow<Int> =
        session.work.queue
            .map { entries -> entries.count { it.stage == WorkStage.AI_ENRICHMENT } }
            .distinctUntilChanged()
            .stateIn(viewModelScope, WhileSubscribedTimeout, 0)

    override fun onQueryChange(text: String) {
        queryInput.value = text
    }

    /**
     * Apply an autocomplete suggestion: updates the raw query text and the active filter
     * immediately (bypassing the filter debounce).
     */
    override fun acceptSuggestion(text: String) {
        val completed = completeActiveSearchToken(queryInput.value, text)
        queryInput.value = completed
        _ui.update { it.copy(filter = it.filter.copy(query = completed.ifBlank { null })) }
    }

    override fun setFilter(filter: LibraryFilter) {
        _ui.update { it.copy(filter = filter) }
        queryInput.value = filter.query.orEmpty()
    }

    override fun clearFilter() {
        _ui.update { it.copy(filter = LibraryFilter()) }
        queryInput.value = ""
    }

    /** Review queue preset: the review mode's [reviewQueueFilter], replacing any active filter. */
    override fun applyReviewPreset() {
        _ui.update { it.copy(filter = reviewMode.value.reviewQueueFilter()) }
        queryInput.value = ""
    }

    override fun setSort(
        field: SortField,
        ascending: Boolean,
    ) {
        sortOverridden = true
        val sort = LibrarySort(field, ascending)
        _ui.update { it.copy(sort = sort) }
        // Persist so the choice survives restart.
        launchReporting(Res.string.common_save_setting_failed) { settings.edit { it.copy(librarySort = sort) } }
    }

    override fun setViewType(v: ViewType) {
        viewOverridden = true
        _ui.update { it.copy(viewType = v) }
        // Persist so the choice becomes the default and survives restart (also keeps the settings
        // screen in sync with the top-bar switcher).
        launchReporting(Res.string.common_save_setting_failed) { settings.edit { it.copy(defaultViewType = v) } }
    }

    override fun applySavedView(view: SavedView) {
        viewOverridden = true
        // Guards the seed only; a saved view's sort is part of the view, not a new default.
        sortOverridden = true
        _ui.update { it.copy(filter = view.filter, sort = view.sort, viewType = view.viewType) }
        queryInput.value = view.filter.query.orEmpty()
    }

    /**
     * Persist the grid cell width (dp) chosen by direct manipulation (pinch / ctrl+scroll / zoom
     * buttons) on the grid. The grid previews changes locally and commits through here.
     */
    override fun setGridColumnWidth(dp: Int) =
        launchReporting(Res.string.common_save_setting_failed) { settings.edit { it.copy(gridColumnWidth = dp) } }

    override fun toggleSelection(docId: String) {
        _ui.update { cur ->
            val next = if (docId in cur.selected) cur.selected - docId else cur.selected + docId
            cur.copy(selected = next)
        }
    }

    override fun startSelection(docId: String) {
        _ui.update { it.copy(selected = setOf(docId)) }
    }

    /** Enter selection mode with every visible document selected (desktop Ctrl+A / Select all). */
    override fun selectAll(docIds: List<String>) {
        _ui.update { it.copy(selected = docIds.toSet()) }
    }

    override fun clearSelection() {
        _ui.update { it.copy(selected = emptySet()) }
    }

    /** Dismiss the issues banner of the scan report shown now, until another report replaces it. */
    override fun dismissBanner() {
        _ui.update { it.copy(dismissedScanReport = lastScanReport.value) }
    }

    /** Rescan the library folder (pull to refresh); a rescan that fails says so. */
    override fun refresh() =
        launchReporting(Res.string.library_refresh_failed) {
            _ui.update { it.copy(refreshing = true) }
            try {
                ingest.rescan()
            } finally {
                _ui.update { it.copy(refreshing = false) }
            }
        }

    // Tag/reviewed writes are a single batched write call. Trash has its own per-document path
    // below because successful moves must be retained for Undo when another move fails.
    fun bulkAddTag(tag: String) = runOnSelection(Res.string.detail_add_tag_failed) { meta.addTag(it, tag) }

    fun bulkRemoveTag(tag: String) = runOnSelection(Res.string.detail_remove_tag_failed) { meta.removeTag(it, tag) }

    /**
     * Tag names carried by at least one of the current selection, sorted. The remove-tag picker
     * offers only these: removing a tag the selection does not carry is a no-op, and the whole
     * vocabulary would bury the handful actually in play. Reads the repository rather than the
     * visible results so a selection that survived a filter change still lists completely.
     */
    override suspend fun tagsInSelection(): List<String> {
        val selected = _ui.value.selected.toList()
        if (selected.isEmpty()) return emptyList()
        return documentRepository
            .tagsForDocuments(selected)
            .values
            .flatten()
            .distinct()
            .sorted()
    }

    override fun bulkSetReviewed(reviewed: Boolean) =
        runOnSelection(Res.string.library_review_failed) {
            meta.setReviewed(it, reviewed)
        }

    /** The bulk trash under way, so a second press while it runs does not trash the selection again. */
    private var trashing: Job? = null

    /**
     * Move the current selection to the trash, one document at a time so a single failure does not
     * abort the rest. The documents it moved are reported with an Undo action that takes back
     * exactly those trashes ([undoTrash]); one already trashed or deleted elsewhere is dropped
     * without a word, as restoring it would undo that; a failed one stays selected for retry, if the
     * user still has it selected. See [runOnSelection] for the per-doc failure contract this mirrors.
     */
    fun bulkTrash() {
        val ids = _ui.value.selected.toList()
        if (ids.isEmpty() || trashing?.isActive == true) return
        trashing =
            viewModelScope.launch {
                val trashed = mutableListOf<TrashReceipt>()
                val failed = forEachReporting(ids, TrashFailed) { id -> docLifecycle.trashDoc(id)?.let(trashed::add) }
                deselect(ids - failed)
                if (trashed.isNotEmpty()) {
                    message(
                        uiText(Res.plurals.library_moved_to_trash, trashed.size, trashed.size),
                        UiMessage.Action(uiText(Res.string.library_undo)) { undoTrash(trashed) },
                    )
                }
            }
    }

    /**
     * Undo [trashes] (the Undo action of the trash message): restore each document while that trash
     * still holds it, not one trashed again since, here or on another device.
     */
    fun undoTrash(trashes: List<TrashReceipt>) {
        if (trashes.isEmpty()) return
        viewModelScope.launch {
            countReporting(trashes.size, Res.plurals.common_restore_documents_failed) {
                docLifecycle.undoTrash(trashes)
            }
        }
    }

    /**
     * Run a bulk op over the current selection.
     *
     * [block] receives all selected ids as one batched call, a single write that lands or fails as a
     * whole. On failure, say why with [failed] and leave the selection as it is, so the user can
     * retry. On success the documents it covered leave the selection.
     */
    private fun runOnSelection(
        failed: StringResource,
        block: suspend (List<String>) -> Unit,
    ) {
        val ids = _ui.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (reporting(failed) { block(ids) }) deselect(ids)
        }
    }

    /**
     * Take [done], the documents a finished bulk action handled, out of the selection as it is now.
     * The selection stays usable while the action runs, so whatever the user selected or deselected
     * meanwhile stands; selection mode ends once nothing is left selected.
     */
    private fun deselect(done: Collection<String>) {
        _ui.update { state ->
            state.copy(selected = state.selected - done.toSet())
        }
    }

    /** Import PDFs from external sources, tallying the per-source outcome into a message. */
    override fun import(sources: List<Pair<ExternalSource, String>>) =
        launchReporting(Res.string.library_import_failed) {
            message(ingest.importAll(sources).asUiText())
        }

    /** What a platform scan came to: import the PDF, say the scanner failed, or stay quiet. */
    override fun importScan(result: ScanResult) {
        when (result) {
            is ScanResult.Scanned -> {
                launchReporting(Res.string.common_scan_import_failed) {
                    message(scanOutcomeMessage(ingest.importPdf(result.pdf.source, result.pdf.suggestedName)))
                }
            }

            ScanResult.Failed -> {
                message(uiText(Res.string.common_scan_capture_failed))
            }

            ScanResult.Cancelled -> {}
        }
    }

    private fun scanOutcomeMessage(outcome: ImportOutcome): UiText =
        when (outcome) {
            is ImportOutcome.Imported -> uiText(Res.string.common_scan_imported)
            is ImportOutcome.Duplicate -> uiText(Res.string.common_scan_already_in_library)
            is ImportOutcome.Failed -> uiText(Res.string.common_scan_import_failed, outcome.reason.text())
        }

    // Names are trimmed here so the trimming rule lives in one place rather than in each dialog.

    /** Capture the live filter/sort/view UI state as a new [SavedView] named [name] and persist it. */
    fun saveCurrentView(name: String) =
        launchReporting(Res.string.library_save_view_failed) {
            savedViewRepository.save(newSavedView(_ui.value, name.trim()))
        }

    /** Persist [view] under a new, whitespace-trimmed [name]. */
    fun renameSavedView(
        view: SavedView,
        name: String,
    ) = launchReporting(Res.string.library_save_view_failed) { savedViewRepository.save(view.copy(name = name.trim())) }

    /** Flip the pinned state of [view] and persist it. */
    override fun toggleSavedViewPin(view: SavedView) =
        launchReporting(Res.string.library_save_view_failed) {
            savedViewRepository.save(view.copy(pinned = !view.pinned))
        }

    /** Delete [view] by id. */
    override fun deleteSavedView(view: SavedView) =
        launchReporting(Res.string.library_delete_view_failed) { savedViewRepository.delete(view.id) }

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

    private companion object {
        val TrashFailed = BulkFailure(Res.string.detail_trash_failed, Res.plurals.library_trash_failed)
        const val QUERY_DEBOUNCE_MS = 300L
        const val SUGGESTION_DEBOUNCE_MS = 100L
        const val SUGGESTION_LIMIT = 8
        const val MIN_SUGGESTION_LENGTH = 2
    }
}

@OptIn(ExperimentalUuidApi::class)
private fun randomId(): String = Uuid.random().toString()
