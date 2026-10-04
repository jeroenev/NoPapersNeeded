@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LabelOff
import androidx.compose.material.icons.filled.NewLabel
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.di.AppEnvironment
import dev.npn.app.platform.LocalDocumentScanner
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.ScrollToTopOnNewResults
import dev.npn.app.ui.common.ScrollbarWidth
import dev.npn.app.ui.common.SearchField
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.TopBarAction
import dev.npn.app.ui.common.TopBarActions
import dev.npn.app.ui.common.VerticalScrollbar
import dev.npn.app.ui.common.isLandscape
import dev.npn.app.ui.common.platformFileToExternalSource
import dev.npn.app.ui.queue.QueueCounts
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SortField
import dev.npn.core.app.ViewType
import dev.npn.core.model.FieldDef
import dev.npn.core.model.ReviewMode
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_add_tag
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_select_all
import no_papers_needed.composeapp.generated.resources.library_add
import no_papers_needed.composeapp.generated.resources.library_ai_edit
import no_papers_needed.composeapp.generated.resources.library_clear_filters
import no_papers_needed.composeapp.generated.resources.library_conflict_file_count
import no_papers_needed.composeapp.generated.resources.library_dismiss
import no_papers_needed.composeapp.generated.resources.library_documents
import no_papers_needed.composeapp.generated.resources.library_duplicate_count
import no_papers_needed.composeapp.generated.resources.library_filters
import no_papers_needed.composeapp.generated.resources.library_import
import no_papers_needed.composeapp.generated.resources.library_import_pdf
import no_papers_needed.composeapp.generated.resources.library_mark_needs_review
import no_papers_needed.composeapp.generated.resources.library_mark_reviewed
import no_papers_needed.composeapp.generated.resources.library_more_actions
import no_papers_needed.composeapp.generated.resources.library_move_to_trash_question
import no_papers_needed.composeapp.generated.resources.library_no_documents_subtitle
import no_papers_needed.composeapp.generated.resources.library_no_documents_title
import no_papers_needed.composeapp.generated.resources.library_no_matches_subtitle
import no_papers_needed.composeapp.generated.resources.library_no_matches_title
import no_papers_needed.composeapp.generated.resources.library_ocr_pending
import no_papers_needed.composeapp.generated.resources.library_ocr_pending_running
import no_papers_needed.composeapp.generated.resources.library_refresh
import no_papers_needed.composeapp.generated.resources.library_remove_tag
import no_papers_needed.composeapp.generated.resources.library_results_summary
import no_papers_needed.composeapp.generated.resources.library_review
import no_papers_needed.composeapp.generated.resources.library_scan_document
import no_papers_needed.composeapp.generated.resources.library_scan_progress
import no_papers_needed.composeapp.generated.resources.library_selected_count
import no_papers_needed.composeapp.generated.resources.library_sort
import no_papers_needed.composeapp.generated.resources.library_sort_added
import no_papers_needed.composeapp.generated.resources.library_sort_ascending
import no_papers_needed.composeapp.generated.resources.library_sort_descending
import no_papers_needed.composeapp.generated.resources.library_sort_name
import no_papers_needed.composeapp.generated.resources.library_sort_size
import no_papers_needed.composeapp.generated.resources.library_switch_to_grid
import no_papers_needed.composeapp.generated.resources.library_switch_to_list
import no_papers_needed.composeapp.generated.resources.library_trash
import no_papers_needed.composeapp.generated.resources.library_trash_documents
import no_papers_needed.composeapp.generated.resources.queue_ai_pending
import no_papers_needed.composeapp.generated.resources.ux_review_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Width of the right-hand side panel (filter editor / document preview) on wide layouts: half the
 * window, clamped so it neither collapses just past the breakpoint nor eats a huge desktop window.
 * The panel is the full document view on wide layouts (viewer + metadata), so it gets equal
 * footing with the list. Its maximum stays below [dev.npn.app.ui.common.WideLayoutBreakpoint],
 * which is what lets the panel lay its content out as a compact window does.
 */
private const val SIDE_PANEL_FRACTION = 0.5f
private val SidePanelMinWidth = 420.dp
private val SidePanelMaxWidth = 800.dp

@Composable
fun LibraryScreen(
    session: LibrarySession,
    env: AppEnvironment,
    onOpenDoc: (docId: String, order: List<String>, searchQuery: String?) -> Unit,
    onTrash: () -> Unit,
    /** Opens the processing queue page, behind the OCR / AI-enrichment progress banners. */
    onOpenQueue: () -> Unit,
    /** Opens the AI-edit page, optionally scoped to the given documents (empty = no preselection). */
    onAiEdit: (List<String>) -> Unit,
    filterHandoff: LibraryFilterHandoff? = null,
) {
    val vm: LibraryViewModel = viewModel { LibraryViewModel(session, env.settings) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val results by vm.documents.collectAsStateWithLifecycle()
    val docs = results?.items

    LaunchedEffect(Unit) {
        filterHandoff?.consume()?.let(vm::setFilter)
    }

    // Opening a doc records the current sorted+filtered order so the detail screen can page between
    // documents; ids are stable so the order stays valid across navigation. The originating search
    // query is also passed through so the detail screen can highlight matching terms.
    val openDoc: (String) -> Unit = { docId ->
        onOpenDoc(docId, docs.orEmpty().map { it.doc.docId }, ui.filter.query)
    }
    val tags by vm.tags.collectAsStateWithLifecycle()
    val fieldFilters by vm.fieldFilters.collectAsStateWithLifecycle()
    val reviewMode by vm.reviewMode.collectAsStateWithLifecycle()
    val aiConfigured by vm.aiConfigured.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    var dialog by rememberLibraryDialog()

    val wide = LocalWindowWidthClass.current.isWide
    // Measured for the side panel's width only; whether there is a side panel is the window's call.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Wide layouts preview a tapped document in the side panel; compact ones open it directly.
        var previewDocId by remember { mutableStateOf<String?>(null) }
        val onDocClick: (String) -> Unit = { docId -> if (wide) previewDocId = docId else openDoc(docId) }

        val sidePanelWidth = (maxWidth * SIDE_PANEL_FRACTION).coerceIn(SidePanelMinWidth, SidePanelMaxWidth)
        val panel = Modifier.width(sidePanelWidth).fillMaxHeight()
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                LibraryContent(
                    vm = vm,
                    content = session.content,
                    wide = wide,
                    snackbar = snackbar,
                    onOpenDoc = onDocClick,
                    onOpenQueue = onOpenQueue,
                    onOpenTrash = onTrash,
                    onAiEdit = onAiEdit.takeIf { aiConfigured },
                    onDialog = { dialog = it },
                )
            }
            // Wide layouts give the side panel to the filter editor when it is open, else to the
            // tapped document's preview, which vanishes when its document leaves the current result
            // set (trashed, filtered out, library rescan). Compact layouts edit the filter in a sheet.
            val items = docs.orEmpty()
            val previewItem = items.firstOrNull { it.doc.docId == previewDocId }.takeIf { wide }
            when {
                dialog == LibraryDialog.Filter -> {
                    val apply = { applied: LibraryFilter ->
                        vm.setFilter(applied)
                        dialog = null
                    }
                    if (wide) {
                        FilterPanel(
                            filter = ui.filter,
                            tags = tags,
                            fields = fieldFilters,
                            reviewMode = reviewMode,
                            onApply = apply,
                            onDismiss = { dialog = null },
                            fillHeight = true,
                            modifier = panel,
                        )
                    } else {
                        FilterSheet(
                            filter = ui.filter,
                            tags = tags,
                            fields = fieldFilters,
                            reviewMode = reviewMode,
                            onApply = apply,
                            onDismiss = { dialog = null },
                        )
                    }
                }

                previewItem != null -> {
                    // By id, not by value: indexOf would run a full structural comparison per
                    // row on every recomposition of this branch.
                    val nextId = items.getOrNull(items.indexOfFirst { it.doc.docId == previewDocId } + 1)?.doc?.docId
                    DocPreviewPanel(
                        item = previewItem,
                        session = session,
                        snackbar = snackbar,
                        onClose = { previewDocId = null },
                        onExpand = { openDoc(previewItem.doc.docId) },
                        onReviewed = { if (previewDocId == previewItem.doc.docId) previewDocId = nextId },
                        hasNext = nextId != null,
                        modifier = panel,
                    )
                }
            }
        }

        LibraryDialogHost(
            dialog = dialog,
            selectedCount = ui.selected.size,
            tagNames = remember(tags) { tags.map { it.name } },
            onSaveView = vm::saveCurrentView,
            onRenameView = vm::renameSavedView,
            onAddTag = vm::bulkAddTag,
            onRemoveTag = vm::bulkRemoveTag,
            onTrash = vm::bulkTrash,
            onDismiss = { dialog = null },
        )
    }
}

/**
 * Desktop keyboard shortcuts for the library screen. Returns true when the event was handled.
 *
 * This is the thin Compose adapter: it gates on key-down + search-not-focused, translates the
 * Compose [KeyEvent] into a pure [LibraryKeyPress], and dispatches the [LibraryShortcut] the pure
 * [libraryShortcutFor] decides on. The shortcut semantics themselves (which keys map to what, and
 * Escape's two-branch behaviour) are asserted in tests against [libraryShortcutFor], not here.
 */
private fun handleLibraryKeyEvent(
    event: KeyEvent,
    searchFocused: Boolean,
    searchFocus: FocusRequester,
    selectionMode: Boolean,
    onImport: () -> Unit,
    onSelectAll: () -> Unit,
    onRequestTrash: () -> Unit,
    onClearSelection: () -> Unit,
    onClearQuery: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown || searchFocused) return false
    val libraryKey = event.toLibraryKey() ?: return false
    val shortcut =
        libraryShortcutFor(LibraryKeyPress(libraryKey, event.isCtrlPressed), selectionMode)
            ?: return false
    when (shortcut) {
        LibraryShortcut.FOCUS_SEARCH -> searchFocus.requestFocus()
        LibraryShortcut.IMPORT -> onImport()
        LibraryShortcut.SELECT_ALL -> onSelectAll()
        LibraryShortcut.REQUEST_TRASH -> onRequestTrash()
        LibraryShortcut.CLEAR_SELECTION -> onClearSelection()
        LibraryShortcut.CLEAR_QUERY -> onClearQuery()
    }
    return true
}

/** Map a Compose [Key] to the pure [LibraryKey] the shortcut map understands, or null. */
private fun KeyEvent.toLibraryKey(): LibraryKey? =
    when (key) {
        Key.F -> LibraryKey.F
        Key.O -> LibraryKey.O
        Key.A -> LibraryKey.A
        Key.Slash -> LibraryKey.SLASH
        Key.Delete -> LibraryKey.DELETE
        Key.Escape -> LibraryKey.ESCAPE
        else -> null
    }

/**
 * The library list with its bars, search, chips and banners. Collects what it shows from [vm]
 * itself; the screen hands over only what the list cannot know: the layout width, the snackbar it
 * shares with the preview panel, where navigation goes, and [onDialog] for the dialogs the screen
 * hosts. [onAiEdit] is null while no AI endpoint is configured.
 */
@Composable
private fun LibraryContent(
    vm: LibraryViewModel,
    content: DocContentFacade,
    // Wide layouts show more top-bar actions as direct icons before spilling them into the overflow
    // menu, and preview a tapped document in the side panel, so its click label reads "Preview"
    // where compact layouts open it and read "Open".
    wide: Boolean,
    snackbar: SnackbarHostState,
    onOpenDoc: (String) -> Unit,
    onOpenQueue: () -> Unit,
    onOpenTrash: () -> Unit,
    onAiEdit: ((selection: List<String>) -> Unit)?,
    onDialog: (LibraryDialog) -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    // Read once, not delegated: the empty and loading branches below smart-cast it.
    val results = vm.documents.collectAsStateWithLifecycle().value
    val tags by vm.tags.collectAsStateWithLifecycle()
    val reviewMode by vm.reviewMode.collectAsStateWithLifecycle()
    val reviewCount by vm.needsReviewCount.collectAsStateWithLifecycle()
    val savedViews by vm.savedViews.collectAsStateWithLifecycle()
    val scanProgress by vm.scanProgress.collectAsStateWithLifecycle()
    val lastScan by vm.lastScanReport.collectAsStateWithLifecycle()
    val ocr by vm.ocrStatus.collectAsStateWithLifecycle()
    val queueCounts by vm.queueCounts.collectAsStateWithLifecycle()
    val sortableFields by vm.sortableFields.collectAsStateWithLifecycle()
    val fieldFilters by vm.fieldFilters.collectAsStateWithLifecycle()
    val queryText by vm.queryText.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val gridColumnWidth by vm.gridColumnWidth.collectAsStateWithLifecycle()
    val landscapeListColumns by vm.landscapeListColumns.collectAsStateWithLifecycle()
    val tagColors = remember(tags) { TagColors(tags.associate { it.name to it.color }) }
    val allDocIds = remember(results) { results?.items.orEmpty().map { it.doc.docId } }

    val importPicker =
        rememberFilePickerLauncher(
            type = FileKitType.File("pdf"),
            mode = FileKitMode.Multiple(),
        ) { files ->
            if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
            vm.import(files.map { platformFileToExternalSource(it) to it.name })
        }
    val onImport: () -> Unit = { importPicker.launch() }
    // Null without a scanner (desktop); the scan's result comes back to the viewmodel, even when
    // this activity was recreated while the scanner was open.
    val scan = LocalDocumentScanner.current?.rememberLauncher(vm::importScan)

    val scope = rememberCoroutineScope()
    val onAddTag = { onDialog(LibraryDialog.AddTag) }
    // Only the tags the selection actually carries: removing any other is a no-op.
    val onRemoveTag: () -> Unit = { scope.launch { onDialog(LibraryDialog.RemoveTag(vm.tagsInSelection())) } }
    val onRequestTrash = { onDialog(LibraryDialog.ConfirmTrash) }
    // Hand the selection to the AI-edit page and leave selection mode behind.
    val aiEditSelection: (() -> Unit)? =
        onAiEdit?.let { open ->
            {
                val selected = ui.selected.toList()
                vm.clearSelection()
                open(selected)
            }
        }
    val searchFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    // Right-click context menu actions. Tag/trash actions operate on the current selection, so the
    // clicked document is pulled into it first (replacing the selection when it was not part of it).
    // The factory is remembered (reading the live selection through State) so the per-item actions
    // it produces can be memoized too instead of invalidating every list item each recomposition.
    val selectedNow by rememberUpdatedState(ui.selected)
    val contextActionsFor: (String) -> DocContextMenuActions =
        remember(vm, onOpenDoc, onAddTag, onRemoveTag, onRequestTrash) {
            { docId ->
                val ensureInSelection = {
                    if (docId !in selectedNow) vm.startSelection(docId)
                }
                DocContextMenuActions(
                    onOpen = { onOpenDoc(docId) },
                    onAddTag = {
                        ensureInSelection()
                        onAddTag()
                    },
                    onRemoveTag = {
                        ensureInSelection()
                        onRemoveTag()
                    },
                    onTrash = {
                        ensureInSelection()
                        onRequestTrash()
                    },
                )
            }
        }
    // Give the shortcut host focus on entry; key events only reach it once something is focused.
    // Wait for the first frame so the requester node is attached to the focus owner before asking
    // (requesting during the first composition otherwise warns "FocusRequester is not initialized").
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { contentFocus.requestFocus() }
    }
    Scaffold(
        topBar = {
            if (ui.selectionMode) {
                SelectionTopBar(
                    count = ui.selected.size,
                    total = allDocIds.size,
                    reviewMode = reviewMode,
                    wide = wide,
                    onClose = vm::clearSelection,
                    onSelectAll = { vm.selectAll(allDocIds) },
                    onAddTag = onAddTag,
                    onRemoveTag = onRemoveTag,
                    onAiEdit = aiEditSelection,
                    onSetReviewed = vm::bulkSetReviewed,
                    onRequestTrash = onRequestTrash,
                )
            } else {
                LibraryTopBar(
                    viewType = ui.viewType,
                    sort = ui.sort,
                    sortableFields = sortableFields,
                    wide = wide,
                    extras = {
                        LibraryViewsMenuButton(
                            reviewMode = reviewMode,
                            reviewBadge = reviewCount,
                            savedViews = savedViews,
                            onAllDocuments = vm::clearFilter,
                            onReviewQueue = vm::applyReviewPreset,
                            onSaveCurrentView = { onDialog(LibraryDialog.SaveView) },
                            onSavedView = vm::applySavedView,
                            onRenameSavedView = { onDialog(LibraryDialog.RenameView(it)) },
                            onDeleteSavedView = vm::deleteSavedView,
                            onTogglePinSavedView = vm::toggleSavedViewPin,
                        )
                    },
                    onOpenFilter = { onDialog(LibraryDialog.Filter) },
                    onSetSort = vm::setSort,
                    onSetView = vm::setViewType,
                    onRefresh = vm::refresh,
                )
            }
        },
        floatingActionButton = {
            if (!ui.selectionMode) {
                LibraryFab(
                    scanAvailable = scan != null,
                    onImport = onImport,
                    onScan = { scan?.invoke() },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // Desktop shortcuts. The container is focusable and takes focus on entry so the keys work
        // before anything is clicked; they only fire when the search field does not hold focus, so
        // typing and the field's own keys (escape to close suggestions) are never swallowed.
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .focusRequester(contentFocus)
                .focusable()
                .onKeyEvent { event ->
                    handleLibraryKeyEvent(
                        event = event,
                        searchFocused = searchFocused,
                        searchFocus = searchFocus,
                        selectionMode = ui.selectionMode,
                        onImport = onImport,
                        onSelectAll = { if (allDocIds.isNotEmpty()) vm.selectAll(allDocIds) },
                        onRequestTrash = onRequestTrash,
                        onClearSelection = vm::clearSelection,
                        onClearQuery = { vm.onQueryChange("") },
                    )
                },
        ) {
            SearchField(
                value = queryText,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                suggestions = suggestions,
                onSuggestionSelected = vm::acceptSuggestion,
                focusRequester = searchFocus,
                onFocusChange = { searchFocused = it },
            )
            // A visible way into the review queue, as a selectable filter affordance rather than a
            // plain counter. It sits below the search field, in the same band as ActiveFilters (which
            // already grows and shrinks), so appearing/disappearing never shifts the search box. It
            // only appears when there is something to review, so a cleared inbox does not leave a
            // permanent "0" in the way. The chip's selected state mirrors whether the INBOX_WORKFLOW
            // preset (unreviewedOrUntagged) is the active filter, so it reads as a filter, not a count.
            if (reviewMode == ReviewMode.INBOX_WORKFLOW && !ui.selectionMode && reviewCount > 0) {
                val reviewFilterActive = ui.filter.unreviewedOrUntagged
                FilterChip(
                    selected = reviewFilterActive,
                    // Re-tapping drops only this facet, the normal behaviour of a selected filter
                    // chip. Not clearFilter(): that also wipes the search box and every other
                    // facet the user narrowed the queue with after applying the preset.
                    onClick = {
                        if (reviewFilterActive) {
                            vm.setFilter(ui.filter.copy(unreviewedOrUntagged = false))
                        } else {
                            vm.applyReviewPreset()
                        }
                    },
                    label = { Text(stringResource(Res.string.ux_review_count, reviewCount)) },
                    leadingIcon = {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(FilterChipDefaults.IconSize),
                        )
                    },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            ActiveFilters(
                filter = ui.filter,
                fields = fieldFilters.fields,
                onFilterChange = vm::setFilter,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Banners(
                scanProgress = scanProgress,
                lastScan = lastScan,
                ocr = ocr,
                queueCounts = queueCounts,
                dismissed = ui.bannerDismissed,
                onDismiss = vm::dismissBanner,
                onGoToTrash = onOpenTrash,
                onOpenQueue = onOpenQueue,
            )

            // A concise "N documents · sorted by …" line sits just above the results, in the fixed
            // band so it never scrolls away. Only shown once the first result set has arrived and
            // is non-empty (the empty states carry their own copy).
            results?.items?.takeIf { it.isNotEmpty() }?.let { items ->
                ResultsSummary(
                    count = items.size,
                    sort = ui.sort,
                    sortableFields = sortableFields,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (results == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (results.items.isEmpty()) {
                    if (ui.filter.isEmpty) {
                        EmptyState(
                            icon = Icons.Filled.Description,
                            title = stringResource(Res.string.library_no_documents_title),
                            subtitle = stringResource(Res.string.library_no_documents_subtitle),
                            actionLabel = stringResource(Res.string.library_import_pdf),
                            onAction = onImport,
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Filled.Description,
                            title = stringResource(Res.string.library_no_matches_title),
                            subtitle = stringResource(Res.string.library_no_matches_subtitle),
                            actionLabel = stringResource(Res.string.library_clear_filters),
                            onAction = vm::clearFilter,
                        )
                    }
                } else {
                    when (ui.viewType) {
                        ViewType.GRID -> {
                            DocGrid(
                                results = results,
                                content = content,
                                tagColors = tagColors,
                                reviewMode = reviewMode,
                                ui = ui,
                                columnWidth = gridColumnWidth,
                                onColumnWidthChange = vm::setGridColumnWidth,
                                onOpen = onOpenDoc,
                                onToggleSelect = vm::toggleSelection,
                                onStartSelection = vm::startSelection,
                                contextActionsFor = contextActionsFor,
                                previewOnClick = wide,
                            )
                        }

                        ViewType.LIST -> {
                            DocList(
                                results = results,
                                content = content,
                                tagColors = tagColors,
                                reviewMode = reviewMode,
                                ui = ui,
                                landscapeColumns = landscapeListColumns,
                                onOpen = onOpenDoc,
                                onToggleSelect = vm::toggleSelection,
                                onStartSelection = vm::startSelection,
                                contextActionsFor = contextActionsFor,
                                previewOnClick = wide,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DocList(
    results: DocumentResults,
    content: dev.npn.core.app.DocContentFacade,
    tagColors: TagColors,
    reviewMode: ReviewMode,
    ui: LibraryUiState,
    landscapeColumns: Boolean,
    onOpen: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onStartSelection: (String) -> Unit,
    contextActionsFor: ((String) -> DocContextMenuActions)? = null,
    previewOnClick: Boolean = false,
) {
    val row: @Composable (dev.npn.core.app.DocListItem) -> Unit = { item ->
        // Memoized per item (this runs in the item's composition scope) so each row receives the
        // same callback/action instances across recompositions and stays skippable while scrolling.
        val id = item.doc.docId
        val selectionMode = ui.selectionMode
        val onClick =
            remember(id, selectionMode, onToggleSelect, onOpen) {
                { if (selectionMode) onToggleSelect(id) else onOpen(id) }
            }
        val onLongClick = remember(id, onStartSelection) { { onStartSelection(id) } }
        val onToggle = remember(id, onToggleSelect) { { onToggleSelect(id) } }
        val contextActions = remember(id, contextActionsFor) { contextActionsFor?.invoke(id) }
        DocListRow(
            item = item,
            content = content,
            tagColors = tagColors,
            reviewMode = reviewMode,
            selectionMode = selectionMode,
            selected = id in ui.selected,
            onClick = onClick,
            onLongClick = onLongClick,
            onToggleSelect = onToggle,
            contextActions = contextActions,
            previewOnClick = previewOnClick,
        )
    }

    // Multi-column list when the option is on and the content area is landscape; otherwise the
    // classic single-column list. Two columns keeps each row wide enough to stay readable.
    // Rows keep an end gutter clear so their content never sits under the scrollbar.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (landscapeColumns && isLandscape) {
            val gridState = rememberLazyGridState()
            ScrollToTopOnNewResults(results.query, gridState)
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                state = gridState,
                modifier = Modifier.fillMaxSize().padding(end = ScrollbarWidth),
            ) {
                items(results.items, key = { it.doc.docId }) { item ->
                    Column {
                        row(item)
                        HorizontalDivider()
                    }
                }
            }
            VerticalScrollbar(gridState)
        } else {
            val listState = rememberLazyListState()
            ScrollToTopOnNewResults(results.query, listState)
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(end = ScrollbarWidth),
            ) {
                items(results.items, key = { it.doc.docId }) { item ->
                    row(item)
                    HorizontalDivider()
                }
            }
            VerticalScrollbar(listState)
        }
    }
}

@Composable
private fun LibraryTopBar(
    viewType: ViewType,
    sort: dev.npn.core.app.LibrarySort,
    sortableFields: List<FieldDef>,
    wide: Boolean,
    extras: @Composable () -> Unit,
    onOpenFilter: () -> Unit,
    onSetSort: (SortField, Boolean) -> Unit,
    onSetView: (ViewType) -> Unit,
    onRefresh: () -> Unit,
) {
    val next = if (viewType == ViewType.LIST) ViewType.GRID else ViewType.LIST
    val switchView =
        TopBarAction(
            // The icon names the view being switched *to*, matching the label.
            icon = if (next == ViewType.GRID) Icons.Filled.GridView else Icons.AutoMirrored.Filled.List,
            // Whole sentences, not an interpolated view name: Dutch compounds it into one word and
            // German capitalises it, so no single template fits every language.
            label =
                stringResource(
                    if (next == ViewType.GRID) Res.string.library_switch_to_grid else Res.string.library_switch_to_list,
                ),
            onClick = { onSetView(next) },
        )
    val refresh = TopBarAction(Icons.Filled.Refresh, stringResource(Res.string.library_refresh), onRefresh)
    TopAppBar(
        title = { Text(stringResource(Res.string.library_documents), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            extras()
            TooltipIconButton(Icons.Filled.FilterList, stringResource(Res.string.library_filters), onOpenFilter)
            SortMenuButton(sort, sortableFields, onSetSort)
            // A desktop window has room for the view and refresh toggles beside filter and sort;
            // a compact bar keeps both one tap away in the overflow menu.
            val (inline, overflow) =
                if (wide) {
                    listOf(switchView, refresh) to emptyList()
                } else {
                    emptyList<TopBarAction>() to listOf(switchView, refresh)
                }
            TopBarActions(
                inline = inline,
                overflow = overflow,
                overflowLabel = stringResource(Res.string.library_more_actions),
            )
        },
    )
}

@Composable
private fun SortMenuButton(
    sort: dev.npn.core.app.LibrarySort,
    sortableFields: List<FieldDef>,
    onSetSort: (SortField, Boolean) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    // Document columns first, then every sortable registry field in registry order.
    val options =
        listOf(SortField.Name to null, SortField.Added to null, SortField.Size to null) +
            sortableFields.map { SortField.Field(it.key) to it }
    Box {
        TooltipIconButton(Icons.AutoMirrored.Filled.Sort, stringResource(Res.string.library_sort), { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { (field, def) ->
                DropdownMenuItem(
                    text = { Text(def?.label ?: sortLabel(field)) },
                    leadingIcon = { RadioButton(selected = sort.field == field, onClick = null) },
                    onClick = {
                        onSetSort(field, sort.ascending)
                        expanded = false
                    },
                )
            }
            HorizontalDivider()
            listOf(true, false).forEach { ascending ->
                DropdownMenuItem(
                    text = {
                        val label =
                            if (ascending) {
                                Res.string.library_sort_ascending
                            } else {
                                Res.string.library_sort_descending
                            }
                        Text(stringResource(label))
                    },
                    leadingIcon = { RadioButton(selected = sort.ascending == ascending, onClick = null) },
                    onClick = {
                        onSetSort(sort.field, ascending)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun SelectionTopBar(
    count: Int,
    total: Int,
    reviewMode: ReviewMode,
    wide: Boolean,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: () -> Unit,
    onAiEdit: (() -> Unit)?,
    onSetReviewed: (Boolean) -> Unit,
    onRequestTrash: () -> Unit,
) {
    val addTag = TopBarAction(Icons.Filled.NewLabel, stringResource(Res.string.common_add_tag), onAddTag)
    val removeTag = TopBarAction(Icons.Filled.LabelOff, stringResource(Res.string.library_remove_tag), onRemoveTag)
    val aiEdit =
        onAiEdit?.let {
            TopBarAction(Icons.Filled.AutoFixHigh, stringResource(Res.string.library_ai_edit), it)
        }
    val trash = TopBarAction(Icons.Filled.Delete, stringResource(Res.string.library_trash), onRequestTrash)
    val selectAll =
        TopBarAction(
            icon = Icons.Filled.SelectAll,
            label = stringResource(Res.string.common_select_all),
            onClick = onSelectAll,
            enabled = total > 0,
        )
    // Adding and removing tags and AI edit are all about the selection itself, so a wide bar shows
    // them beside add-tag and trash. A compact bar keeps just the two frequent actions and leaves
    // remove-tag, AI edit and the reviewed toggles in the overflow menu.
    val inline =
        buildList {
            add(addTag)
            if (wide) add(removeTag)
            if (wide) aiEdit?.let { add(it) }
            add(trash)
        }
    val overflow =
        buildList {
            add(selectAll)
            if (!wide) add(removeTag)
            if (!wide) aiEdit?.let { add(it) }
            if (reviewMode == ReviewMode.INBOX_WORKFLOW) {
                add(
                    TopBarAction(
                        icon = Icons.Filled.CheckCircle,
                        label = stringResource(Res.string.library_mark_reviewed),
                        onClick = { onSetReviewed(true) },
                    ),
                )
                add(
                    TopBarAction(
                        icon = Icons.Filled.RadioButtonUnchecked,
                        label = stringResource(Res.string.library_mark_needs_review),
                        onClick = { onSetReviewed(false) },
                    ),
                )
            }
        }
    TopAppBar(
        title = {
            Text(
                pluralStringResource(Res.plurals.library_selected_count, count, count),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        navigationIcon = { TooltipIconButton(Icons.Filled.Clear, stringResource(Res.string.common_cancel), onClose) },
        actions = {
            TopBarActions(
                inline = inline,
                overflow = overflow,
                overflowLabel = stringResource(Res.string.library_more_actions),
            )
        },
    )
}

@Composable
private fun Banners(
    scanProgress: dev.npn.core.app.ScanProgressUi?,
    lastScan: dev.npn.core.index.ScanReport?,
    ocr: dev.npn.core.app.OcrStatus,
    queueCounts: QueueCounts,
    dismissed: Boolean,
    onDismiss: () -> Unit,
    onGoToTrash: () -> Unit,
    onOpenQueue: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        scanProgress?.let { ScanProgressBanner(it) }
        PathConflictsBanner(lastScan?.pathConflicts.orEmpty())
        val conflicts = lastScan?.conflictFiles.orEmpty()
        val dupes = lastScan?.duplicates.orEmpty()
        if (!dismissed && (conflicts.isNotEmpty() || dupes.isNotEmpty())) {
            ScanIssuesBanner(conflicts.size, dupes.size, onGoToTrash, onDismiss)
        }
        // Both indicators open the same queue view, which is the detail behind either count.
        if (ocr.pending > 0) OcrStatusIndicator(ocr, onOpenQueue)
        if (queueCounts.enrichment > 0) EnrichmentStatusIndicator(queueCounts.enrichment, onOpenQueue)
    }
}

@Composable
private fun ScanProgressBanner(scanProgress: dev.npn.core.app.ScanProgressUi) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(
                stringResource(
                    Res.string.library_scan_progress,
                    scanProgress.phase,
                    scanProgress.done,
                    scanProgress.total,
                ),
                style = MaterialTheme.typography.bodyMedium,
            )
            val fraction =
                if (scanProgress.total > 0) scanProgress.done.toFloat() / scanProgress.total else 0f
            LinearProgressIndicator(
                progress = { fraction },
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun ScanIssuesBanner(
    conflictCount: Int,
    duplicateCount: Int,
    onGoToTrash: () -> Unit,
    onDismiss: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.padding(12.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(Icons.Filled.Warning, contentDescription = null)
            val conflictText =
                if (conflictCount > 0) {
                    pluralStringResource(Res.plurals.library_conflict_file_count, conflictCount, conflictCount)
                } else {
                    null
                }
            val duplicateText =
                if (duplicateCount > 0) {
                    pluralStringResource(Res.plurals.library_duplicate_count, duplicateCount, duplicateCount)
                } else {
                    null
                }
            Text(
                listOfNotNull(conflictText, duplicateText).joinToString(" "),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onGoToTrash) { Text(stringResource(Res.string.library_review)) }
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.library_dismiss)) }
        }
    }
}

@Composable
private fun OcrStatusIndicator(
    ocr: dev.npn.core.app.OcrStatus,
    onOpenQueue: () -> Unit,
) {
    QueueStatusIndicator(
        icon = Icons.Filled.Refresh,
        label =
            if (ocr.running) {
                pluralStringResource(Res.plurals.library_ocr_pending_running, ocr.pending, ocr.pending)
            } else {
                pluralStringResource(Res.plurals.library_ocr_pending, ocr.pending, ocr.pending)
            },
        onClick = onOpenQueue,
    )
}

@Composable
private fun EnrichmentStatusIndicator(
    pending: Int,
    onOpenQueue: () -> Unit,
) {
    QueueStatusIndicator(
        icon = Icons.Filled.AutoAwesome,
        label = pluralStringResource(Res.plurals.queue_ai_pending, pending, pending),
        onClick = onOpenQueue,
    )
}

/** One line of "N documents still waiting", clickable through to the queue view behind the count. */
@Composable
private fun QueueStatusIndicator(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LibraryFab(
    scanAvailable: Boolean,
    onImport: () -> Unit,
    onScan: () -> Unit,
) {
    if (!scanAvailable) {
        ExtendedFloatingActionButton(
            onClick = onImport,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(Res.string.library_import)) },
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(
            onClick = { expanded = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text(stringResource(Res.string.library_add)) },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_scan_document)) },
                leadingIcon = { Icon(Icons.Filled.DocumentScanner, contentDescription = null) },
                onClick = {
                    expanded = false
                    onScan()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_import_pdf)) },
                leadingIcon = { Icon(Icons.Filled.UploadFile, contentDescription = null) },
                onClick = {
                    expanded = false
                    onImport()
                },
            )
        }
    }
}

/**
 * One concise line above the results: how many documents match and how they are ordered. Reads the
 * field label from the registry so a sort on a renamed field still names it correctly; falls back to
 * the sort key for a field the registry no longer lists.
 */
@Composable
private fun ResultsSummary(
    count: Int,
    sort: dev.npn.core.app.LibrarySort,
    sortableFields: List<FieldDef>,
    modifier: Modifier = Modifier,
) {
    val fieldLabel =
        when (val field = sort.field) {
            is SortField.Field -> sortableFields.firstOrNull { it.key == field.key }?.label ?: field.key
            else -> sortLabel(sort.field)
        }
    val direction =
        stringResource(
            if (sort.ascending) Res.string.library_sort_ascending else Res.string.library_sort_descending,
        )
    Text(
        text =
            pluralStringResource(
                Res.plurals.library_results_summary,
                count,
                count,
                "$fieldLabel · $direction",
            ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    )
}

@Composable
private fun sortLabel(field: SortField): String =
    when (field) {
        SortField.Name -> stringResource(Res.string.library_sort_name)

        SortField.Added -> stringResource(Res.string.library_sort_added)

        SortField.Size -> stringResource(Res.string.library_sort_size)

        // A field the registry no longer lists (e.g. a saved sort on a deleted field): show the key.
        is SortField.Field -> field.key
    }
