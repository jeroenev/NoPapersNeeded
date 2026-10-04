package dev.npn.app.ui.library

import androidx.lifecycle.viewModelScope
import dev.npn.app.platform.ScannedPdf
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiAction
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.activeSearchToken
import dev.npn.app.ui.common.aiConfigured
import dev.npn.app.ui.common.completeActiveSearchToken
import dev.npn.app.ui.common.textFieldValues
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.queue.QueueCounts
import dev.npn.core.app.DocLifecycle
import dev.npn.core.app.DocListItem
import dev.npn.core.app.DocumentRepository
import dev.npn.core.app.FieldRegistry
import dev.npn.core.app.ImportFailure
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
import dev.npn.core.util.runCatchingCancellable
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
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_import_failure_copy
import no_papers_needed.composeapp.generated.resources.common_import_failure_empty
import no_papers_needed.composeapp.generated.resources.common_import_failure_images
import no_papers_needed.composeapp.generated.resources.library_import_failed
import no_papers_needed.composeapp.generated.resources.library_moved_to_trash
import no_papers_needed.composeapp.generated.resources.library_refresh_failed
import no_papers_needed.composeapp.generated.resources.library_undo
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

/** Import each of [sources] (a file and the name to give it) as a PDF, and tally the outcomes. */
suspend fun IngestFacade.importPdfs(sources: List<Pair<ExternalSource, String>>): ImportSummary {
    var imported = 0
    var duplicate = 0
    var failed = 0
    sources.forEach { (source, name) ->
        when (importPdf(source, name)) {
            is ImportOutcome.Imported -> imported++
            is ImportOutcome.Duplicate -> duplicate++
            is ImportOutcome.Failed -> failed++
        }
    }
    return ImportSummary(imported, duplicate, failed)
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

    /** Rescan the library folder (pull to refresh); a rescan that fails says so. */
    fun refresh() =
        launchReporting({ reason -> uiText(Res.string.library_refresh_failed, reason) }) {
            _ui.update { it.copy(refreshing = true) }
            try {
                ingest.rescan()
            } finally {
                _ui.update { it.copy(refreshing = false) }
            }
        }

    // Tag/reviewed writes are a single batched write call. Trash has its own per-document path
    // below because successful moves must be retained for Undo when another move fails.
    fun bulkAddTag(tag: String) = runOnSelection(UiAction.ADD_TAG) { meta.addTag(it, tag) }

    fun bulkRemoveTag(tag: String) = runOnSelection(UiAction.REMOVE_TAG) { meta.removeTag(it, tag) }

    /**
     * Tag names carried by at least one of the current selection, sorted. The remove-tag picker
     * offers only these: removing a tag the selection does not carry is a no-op, and the whole
     * vocabulary would bury the handful actually in play. Reads the repository rather than the
     * visible results so a selection that survived a filter change still lists completely.
     */
    suspend fun tagsInSelection(): List<String> {
        val selected = _ui.value.selected.toList()
        if (selected.isEmpty()) return emptyList()
        return documentRepository
            .tagsForDocuments(selected)
            .values
            .flatten()
            .distinct()
            .sorted()
    }

    fun bulkSetReviewed(reviewed: Boolean) = runOnSelection(UiAction.UPDATE) { meta.setReviewed(it, reviewed) }

    /**
     * Move the current selection to the trash, one document at a time so a single failure does not
     * abort the rest. Any successful move is reported with an Undo action that restores exactly the
     * documents moved; the failed ids stay selected for retry. See [runOnSelection] for the per-doc
     * failure contract this mirrors.
     */
    fun bulkTrash() {
        val ids = _ui.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val trashed = ids.filter { runCatchingCancellable { docLifecycle.trashDoc(it) }.isSuccess }
            val failed = ids - trashed.toSet()
            if (failed.isEmpty()) {
                clearSelection()
            } else {
                _ui.update { it.copy(selected = failed.toSet(), selectionMode = true) }
                message(uiText(UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS, UiAction.TRASH, failed.size, ids.size))
            }
            if (trashed.isNotEmpty()) {
                message(
                    uiText(Res.plurals.library_moved_to_trash, trashed.size, trashed.size),
                    UiMessage.Action(uiText(Res.string.library_undo)) { undoTrash(trashed) },
                )
            }
        }
    }

    /** Restore [ids] previously moved to the trash (the Undo action of the trash message). */
    fun undoTrash(ids: List<String>) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val failed = ids.count { runCatchingCancellable { docLifecycle.restoreDoc(it) }.isFailure }
            if (failed > 0) {
                message(uiText(UiTextKey.COULD_NOT_PARTIAL_DOCUMENTS, UiAction.RESTORE_DOCUMENT, failed, ids.size))
            }
        }
    }

    /**
     * Run a bulk op over the current selection.
     *
     * [block] receives all selected ids as one batched call. On failure, surface a message and keep
     * the whole selection so the user can retry. On success the selection is cleared.
     */
    private fun runOnSelection(
        action: UiAction,
        block: suspend (List<String>) -> Unit,
    ) {
        val ids = _ui.value.selected.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            if (runCatchingCancellable { block(ids) }.isSuccess) {
                clearSelection()
            } else {
                message(uiText(UiTextKey.COULD_NOT_DOCUMENTS, action, ids.size))
            }
        }
    }

    /** Import PDFs from external sources, tallying the per-source outcome into a message. */
    fun import(sources: List<Pair<ExternalSource, String>>) =
        launchReporting({ reason -> uiText(Res.string.library_import_failed, reason) }) {
            message(ingest.importPdfs(sources).asUiText())
        }

    /** Launch a platform scan; cancellation stays quiet while an actual scanner failure is visible. */
    fun scan(scanToPdf: suspend () -> ScannedPdf?) {
        viewModelScope.launch {
            val scanned =
                runCatchingCancellable { scanToPdf() }.getOrElse {
                    message(uiText(UiTextKey.SCAN_CAPTURE_FAILED))
                    return@launch
                }
            if (scanned != null) importScanned(scanned)
        }
    }

    private suspend fun importScanned(scanned: ScannedPdf) =
        reporting({ reason -> uiText(UiTextKey.SCAN_IMPORT_FAILED, reason) }) {
            message(scanOutcomeMessage(ingest.importPdf(scanned.source, scanned.suggestedName)))
        }

    private fun scanOutcomeMessage(outcome: ImportOutcome): UiText =
        when (outcome) {
            is ImportOutcome.Imported -> uiText(UiTextKey.SCAN_IMPORTED)
            is ImportOutcome.Duplicate -> uiText(UiTextKey.SCAN_ALREADY_IN_LIBRARY)
            is ImportOutcome.Failed -> uiText(UiTextKey.SCAN_IMPORT_FAILED, outcome.reason.text())
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

/** Why an import added nothing, as the reason clause of a "… failed: %1$s" message. */
private fun ImportFailure.text(): UiText =
    when (this) {
        ImportFailure.EMPTY_FILE -> uiText(Res.string.common_import_failure_empty)
        ImportFailure.UNREADABLE_IMAGES -> uiText(Res.string.common_import_failure_images)
        ImportFailure.COPY_FAILED -> uiText(Res.string.common_import_failure_copy)
    }
