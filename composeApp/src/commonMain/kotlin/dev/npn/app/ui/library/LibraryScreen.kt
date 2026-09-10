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
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.NewLabel
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.ScrollToTopOnNewResults
import dev.npn.app.ui.common.ScrollbarWidth
import dev.npn.app.ui.common.SearchField
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.VerticalScrollbar
import dev.npn.app.ui.common.WideLayoutBreakpoint
import dev.npn.app.ui.common.isLandscape
import dev.npn.app.ui.common.platformFileToExternalSource
import dev.npn.app.ui.queue.QueueCounts
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
import no_papers_needed.composeapp.generated.resources.common_view_grid
import no_papers_needed.composeapp.generated.resources.common_view_list
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
import no_papers_needed.composeapp.generated.resources.library_switch_view
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
 * footing with the list.
 */
private const val SIDE_PANEL_FRACTION = 0.5f
private val SidePanelMinWidth = 420.dp
private val SidePanelMaxWidth = 800.dp

@Composable
fun LibraryScreen(
    session: LibrarySession,
    env: AppEnvironment,
    aiConfigured: Boolean,
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
    val correspondents by vm.correspondents.collectAsStateWithLifecycle()
    val docTypes by vm.docTypes.collectAsStateWithLifecycle()
    val needsReview by vm.needsReviewCount.collectAsStateWithLifecycle()
    val reviewMode by vm.reviewMode.collectAsStateWithLifecycle()
    val savedViews by vm.savedViews.collectAsStateWithLifecycle()
    val scanProgress by vm.scanProgress.collectAsStateWithLifecycle()
    val lastScan by vm.lastScanReport.collectAsStateWithLifecycle()
    val ocr by vm.ocrStatus.collectAsStateWithLifecycle()
    val queueCounts by vm.queueCounts.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    val dialogs = rememberLibraryDialogState()

    val tagColors = remember(tags) { TagColors(tags.associate { it.name to it.color }) }

    val importPicker =
        rememberFilePickerLauncher(
            type = FileKitType.File("pdf"),
            mode = FileKitMode.Multiple(),
        ) { files ->
            if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
            val sources = files.map { platformFileToExternalSource(it) to it.name }
            vm.import(sources)
        }

    val scanner = dev.npn.app.platform.LocalDocumentScanner.current
    val onScan: () -> Unit = {
        scope.launch {
            val bytes = runCatching { scanner.scanToPdf() }.getOrNull()
            if (bytes != null) {
                vm.importScannedPdf(bytes)
            }
        }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > WideLayoutBreakpoint
        // The navigation rail already carries the AI entries on wide layouts, so the top bar only
        // needs them below the wide breakpoint; they stay as direct icons until the window is too
        // tight for them, collapsing into the overflow menu only then.
        // Wide layouts preview a tapped document in the side panel; compact ones open it directly.
        var previewDocId by remember { mutableStateOf<String?>(null) }
        val onDocClick: (String) -> Unit = { docId -> if (wide) previewDocId = docId else openDoc(docId) }

        val topBarExtras: @Composable () -> Unit = {
            LibraryViewsMenuButton(
                reviewMode = reviewMode,
                reviewBadge = needsReview,
                savedViews = savedViews,
                onAllDocuments = vm::clearFilter,
                onReviewQueue = vm::applyReviewPreset,
                onSaveCurrentView = dialogs::openSaveView,
                onSavedView = vm::applySavedView,
                onRenameSavedView = dialogs::openRename,
                onDeleteSavedView = vm::deleteSavedView,
                onTogglePinSavedView = vm::toggleSavedViewPin,
            )
        }

        val content: @Composable () -> Unit = {
            LibraryContent(
                vm = vm,
                ui = ui,
                session = session,
                results = results,
                tagColors = tagColors,
                reviewMode = reviewMode,
                scanProgress = scanProgress,
                lastScan = lastScan,
                ocr = ocr,
                queueCounts = queueCounts,
                onOpenQueue = onOpenQueue,
                snackbar = snackbar,
                topBarExtras = topBarExtras,
                onOpenDoc = onDocClick,
                onTrashBanner = onTrash,
                onImport = { importPicker.launch() },
                onScan = onScan,
                scanAvailable = scanner.available,
                onOpenFilter = dialogs::openFilter,
                // Hand the selection to the AI-edit page and leave selection mode behind.
                onAiEdit =
                    if (!aiConfigured) {
                        null
                    } else {
                        {
                            val selected = ui.selected.toList()
                            vm.clearSelection()
                            onAiEdit(selected)
                        }
                    },
                onAddTag = {
                    scope.launch { dialogs.allTagNames = session.tags.allTagNames() }
                    dialogs.openAddTag()
                },
                onRemoveTag = {
                    scope.launch { dialogs.allTagNames = session.tags.allTagNames() }
                    dialogs.openRemoveTag()
                },
            )
        }

        if (wide) {
            val sidePanelWidth = (maxWidth * SIDE_PANEL_FRACTION).coerceIn(SidePanelMinWidth, SidePanelMaxWidth)
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { content() }
                // The side panel shows the filter editor when it is open, else the tapped
                // document's preview. The preview vanishes automatically when its document
                // leaves the current result set (trashed, filtered out, library rescan).
                val previewItem = docs?.firstOrNull { it.doc.docId == previewDocId }
                when {
                    dialogs.showFilter -> {
                        FilterPanel(
                            filter = ui.filter,
                            tags = tags,
                            correspondents = correspondents,
                            docTypes = docTypes,
                            reviewMode = reviewMode,
                            onApply = {
                                vm.setFilter(it)
                                dialogs.dismissFilter()
                            },
                            onClearAll = vm::clearFilter,
                            onDismiss = dialogs::dismissFilter,
                            fillHeight = true,
                            modifier = Modifier.width(sidePanelWidth).fillMaxHeight(),
                        )
                    }

                    previewItem != null -> {
                        // By id, not by value: indexOf would run a full structural comparison per
                        // row on every recomposition of this branch.
                        val nextId = docs.getOrNull(docs.indexOfFirst { it.doc.docId == previewDocId } + 1)?.doc?.docId
                        DocPreviewPanel(
                            item = previewItem,
                            session = session,
                            snackbar = snackbar,
                            onClose = { previewDocId = null },
                            onExpand = { openDoc(previewItem.doc.docId) },
                            onReviewed = { if (previewDocId == previewItem.doc.docId) previewDocId = nextId },
                            hasNext = nextId != null,
                            modifier = Modifier.width(sidePanelWidth).fillMaxHeight(),
                        )
                    }
                }
            }
        } else {
            content()
        }

        LibraryDialogHost(
            state = dialogs,
            filter = ui.filter,
            selectedCount = ui.selected.size,
            tags = tags,
            correspondents = correspondents,
            docTypes = docTypes,
            reviewMode = reviewMode,
            onApplyFilter = { applied ->
                vm.setFilter(applied)
                dialogs.dismissFilter()
            },
            onClearFilter = vm::clearFilter,
            onSaveView = vm::saveCurrentView,
            onRenameView = vm::renameSavedView,
            onAddTag = vm::bulkAddTag,
            onRemoveTag = vm::bulkRemoveTag,
            renderFilter = !wide,
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

@Composable
private fun LibraryContent(
    vm: LibraryViewModel,
    ui: LibraryUiState,
    session: LibrarySession,
    results: DocumentResults?,
    tagColors: TagColors,
    reviewMode: ReviewMode,
    scanProgress: dev.npn.core.app.ScanProgressUi?,
    lastScan: dev.npn.core.index.ScanReport?,
    ocr: dev.npn.core.app.OcrStatus,
    queueCounts: QueueCounts,
    onOpenQueue: () -> Unit,
    snackbar: SnackbarHostState,
    topBarExtras: @Composable () -> Unit,
    onOpenDoc: (String) -> Unit,
    onTrashBanner: () -> Unit,
    onImport: () -> Unit,
    onScan: () -> Unit,
    scanAvailable: Boolean,
    onOpenFilter: () -> Unit,
    onAiEdit: (() -> Unit)?,
    onAddTag: () -> Unit,
    onRemoveTag: () -> Unit,
) {
    val reviewCount by vm.needsReviewCount.collectAsStateWithLifecycle()
    val sortableFields by vm.sortableFields.collectAsStateWithLifecycle()
    val queryText by vm.queryText.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val gridColumnWidth by vm.gridColumnWidth.collectAsStateWithLifecycle()
    val landscapeListColumns by vm.landscapeListColumns.collectAsStateWithLifecycle()
    val allDocIds = results?.items.orEmpty().map { it.doc.docId }
    val searchFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    // Right-click context menu actions. Tag/trash actions operate on the current selection, so the
    // clicked document is pulled into it first (replacing the selection when it was not part of it).
    // The factory is remembered (reading the live selection through State) so the per-item actions
    // it produces can be memoized too instead of invalidating every list item each recomposition.
    val selectedNow by rememberUpdatedState(ui.selected)
    val contextActionsFor: (String) -> DocContextMenuActions =
        remember(vm, onOpenDoc, onAddTag, onRemoveTag) {
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
                        confirmTrash = true
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
                    onClose = vm::clearSelection,
                    onSelectAll = { vm.selectAll(allDocIds) },
                    onAddTag = onAddTag,
                    onRemoveTag = onRemoveTag,
                    onAiEdit = onAiEdit,
                    onSetReviewed = vm::bulkSetReviewed,
                    onRequestTrash = { confirmTrash = true },
                )
            } else {
                LibraryTopBar(
                    viewType = ui.viewType,
                    sort = ui.sort,
                    sortableFields = sortableFields,
                    extras = topBarExtras,
                    onOpenFilter = onOpenFilter,
                    onSetSort = vm::setSort,
                    onSetView = vm::setViewType,
                    onRefresh = vm::refresh,
                )
            }
        },
        floatingActionButton = {
            if (!ui.selectionMode) {
                LibraryFab(
                    scanAvailable = scanAvailable,
                    onImport = onImport,
                    onScan = onScan,
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
                        onRequestTrash = { confirmTrash = true },
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
                onGoToTrash = onTrashBanner,
                onOpenQueue = onOpenQueue,
            )

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
                                content = session.content,
                                tagColors = tagColors,
                                ui = ui,
                                columnWidth = gridColumnWidth,
                                onColumnWidthChange = vm::setGridColumnWidth,
                                onOpen = onOpenDoc,
                                onToggleSelect = vm::toggleSelection,
                                onStartSelection = vm::startSelection,
                                contextActionsFor = contextActionsFor,
                            )
                        }

                        ViewType.LIST -> {
                            DocList(
                                results = results,
                                content = session.content,
                                tagColors = tagColors,
                                reviewMode = reviewMode,
                                ui = ui,
                                landscapeColumns = landscapeListColumns,
                                onOpen = onOpenDoc,
                                onToggleSelect = vm::toggleSelection,
                                onStartSelection = vm::startSelection,
                                contextActionsFor = contextActionsFor,
                            )
                        }
                    }
                }
            }
        }
    }
    if (confirmTrash) {
        dev.npn.app.ui.common.ConfirmDialog(
            title = stringResource(Res.string.library_trash_documents),
            message =
                pluralStringResource(
                    Res.plurals.library_move_to_trash_question,
                    ui.selected.size,
                    ui.selected.size,
                ),
            confirmLabel = stringResource(Res.string.library_trash),
            onConfirm = {
                vm.bulkTrash()
                confirmTrash = false
            },
            onDismiss = { confirmTrash = false },
        )
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
    extras: @Composable () -> Unit,
    onOpenFilter: () -> Unit,
    onSetSort: (SortField, Boolean) -> Unit,
    onSetView: (ViewType) -> Unit,
    onRefresh: () -> Unit,
) {
    TopAppBar(
        title = { Text(stringResource(Res.string.library_documents), maxLines = 1, overflow = TextOverflow.Ellipsis) },
        actions = {
            extras()
            TooltipIconButton(Icons.Filled.FilterList, stringResource(Res.string.library_filters), onOpenFilter)
            SortMenuButton(sort, sortableFields, onSetSort)
            var expanded by remember { mutableStateOf(false) }
            Box {
                TooltipIconButton(
                    Icons.Filled.MoreVert,
                    stringResource(Res.string.library_more_actions),
                    { expanded = true },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    val next = if (viewType == ViewType.LIST) ViewType.GRID else ViewType.LIST
                    DropdownMenuItem(
                        // Lowercased: the label is interpolated mid-sentence ("Switch to list
                        // view"), and Dutch compounds it into one word.
                        text = {
                            Text(
                                stringResource(Res.string.library_switch_view, viewTypeLabel(next).lowercase()),
                            )
                        },
                        onClick = {
                            expanded = false
                            onSetView(next)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.library_refresh)) },
                        onClick = {
                            expanded = false
                            onRefresh()
                        },
                    )
                }
            }
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
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: () -> Unit,
    onAiEdit: (() -> Unit)?,
    onSetReviewed: (Boolean) -> Unit,
    onRequestTrash: () -> Unit,
) {
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
            TooltipIconButton(Icons.Filled.NewLabel, stringResource(Res.string.common_add_tag), onAddTag)
            TooltipIconButton(Icons.Filled.Delete, stringResource(Res.string.library_trash), onRequestTrash)
            var expanded by remember { mutableStateOf(false) }
            Box {
                TooltipIconButton(
                    Icons.Filled.MoreVert,
                    stringResource(Res.string.library_more_actions),
                    { expanded = true },
                )
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.common_select_all)) },
                        enabled = total > 0,
                        onClick = {
                            expanded = false
                            onSelectAll()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.library_remove_tag)) },
                        onClick = {
                            expanded = false
                            onRemoveTag()
                        },
                    )
                    onAiEdit?.let { edit ->
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.library_ai_edit)) },
                            onClick = {
                                expanded = false
                                edit()
                            },
                        )
                    }
                    if (reviewMode == ReviewMode.INBOX_WORKFLOW) {
                        listOf(true, false).forEach { reviewed ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(
                                            if (reviewed) {
                                                Res.string.library_mark_reviewed
                                            } else {
                                                Res.string.library_mark_needs_review
                                            },
                                        ),
                                    )
                                },
                                onClick = {
                                    expanded = false
                                    onSetReviewed(reviewed)
                                },
                            )
                        }
                    }
                }
            }
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
                onClick = {
                    expanded = false
                    onScan()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.library_import_pdf)) },
                onClick = {
                    expanded = false
                    onImport()
                },
            )
        }
    }
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

@Composable
private fun viewTypeLabel(viewType: ViewType): String =
    when (viewType) {
        ViewType.LIST -> stringResource(Res.string.common_view_list)
        ViewType.GRID -> stringResource(Res.string.common_view_grid)
    }
