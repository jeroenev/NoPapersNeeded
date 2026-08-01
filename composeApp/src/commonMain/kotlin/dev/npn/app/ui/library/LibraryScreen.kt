@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.library

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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material.icons.filled.ZoomOut
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.SearchField
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.isConfiguredOrFalse
import dev.npn.app.ui.common.isLandscape
import dev.npn.app.ui.common.platformFileToExternalSource
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SortField
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_add_tag
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_menu
import no_papers_needed.composeapp.generated.resources.common_select_all
import no_papers_needed.composeapp.generated.resources.common_view_grid
import no_papers_needed.composeapp.generated.resources.common_view_list
import no_papers_needed.composeapp.generated.resources.library_add
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
import no_papers_needed.composeapp.generated.resources.library_sort_by
import no_papers_needed.composeapp.generated.resources.library_sort_descending
import no_papers_needed.composeapp.generated.resources.library_sort_doc_date
import no_papers_needed.composeapp.generated.resources.library_sort_name
import no_papers_needed.composeapp.generated.resources.library_sort_size
import no_papers_needed.composeapp.generated.resources.library_switch_view
import no_papers_needed.composeapp.generated.resources.library_trash
import no_papers_needed.composeapp.generated.resources.library_trash_documents
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun LibraryScreen(
    session: LibrarySession,
    env: AppEnvironment,
    onOpenDoc: (docId: String, order: List<String>, searchQuery: String?) -> Unit,
    onTrash: () -> Unit,
    onTags: () -> Unit,
    onSettings: () -> Unit,
    onAiChat: () -> Unit,
    onAiTagging: () -> Unit,
) {
    // Probe once whether the AI assistant is configured so the entry point stays hidden otherwise.
    val aiConfigured by produceState(initialValue = false, session) {
        value = session.ai.isConfiguredOrFalse()
    }
    val vm: LibraryViewModel = viewModel { LibraryViewModel(session, env.settings) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()

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

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)
    val drawerState =
        androidx.compose.material3.rememberDrawerState(
            androidx.compose.material3.DrawerValue.Closed,
        )

    val dialogs = rememberLibraryDialogState()

    val tagColors = remember(tags) { tags.associate { it.name to it.color } }

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

    val content: @Composable () -> Unit = {
        LibraryContent(
            vm = vm,
            ui = ui,
            session = session,
            docs = docs,
            tagColors = tagColors,
            reviewMode = reviewMode,
            scanProgress = scanProgress,
            lastScan = lastScan,
            ocr = ocr,
            snackbar = snackbar,
            onMenu = { scope.launch { drawerState.open() } },
            onOpenDoc = openDoc,
            onTrashBanner = onTrash,
            onImport = { importPicker.launch() },
            onScan = onScan,
            scanAvailable = scanner.available,
            onOpenFilter = dialogs::openFilter,
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

    fun closeDrawerThen(action: () -> Unit): () -> Unit =
        {
            scope.launch { drawerState.close() }
            action()
        }

    val drawer: @Composable () -> Unit = {
        LibraryDrawerContent(
            reviewMode = reviewMode,
            reviewBadge = needsReview,
            savedViews = savedViews,
            onAllDocuments = {
                vm.clearFilter()
                scope.launch { drawerState.close() }
            },
            onReviewQueue = {
                vm.applyReviewPreset()
                scope.launch { drawerState.close() }
            },
            onTrash = closeDrawerThen(onTrash),
            onTags = closeDrawerThen(onTags),
            aiConfigured = aiConfigured,
            onAiChat = closeDrawerThen(onAiChat),
            onAiTagging = closeDrawerThen(onAiTagging),
            onSettings = closeDrawerThen(onSettings),
            onSavedView = { view ->
                vm.applySavedView(view)
                scope.launch { drawerState.close() }
            },
            onSaveCurrentView = closeDrawerThen { dialogs.openSaveView() },
            onRenameSavedView = dialogs::openRename,
            // Thin pass-throughs to the session; run on the screen scope (quick fire-and-forget).
            onDeleteSavedView = { view -> scope.launch { session.savedViews.delete(view.id) } },
            onTogglePinSavedView = { view ->
                scope.launch { session.savedViews.save(view.copy(pinned = !view.pinned)) }
            },
        )
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > 840.dp
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                PermanentDrawerSheet(Modifier.padding(end = 0.dp)) { drawer() }
                Row(Modifier.weight(1f)) {
                    Box(Modifier.weight(1f)) { content() }
                    if (dialogs.showFilter) {
                        FilterPanel(
                            filter = ui.filter,
                            tags = tags,
                            correspondents = correspondents,
                            docTypes = docTypes,
                            onApply = {
                                vm.setFilter(it)
                                dialogs.dismissFilter()
                            },
                            onClearAll = vm::clearFilter,
                            onDismiss = dialogs::dismissFilter,
                            fillHeight = true,
                            modifier = Modifier.width(380.dp).fillMaxHeight(),
                        )
                    }
                }
            }
        } else {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { ModalDrawerSheet { drawer() } },
            ) { content() }
        }

        LibraryDialogHost(
            state = dialogs,
            filter = ui.filter,
            selectedCount = ui.selected.size,
            tags = tags,
            correspondents = correspondents,
            docTypes = docTypes,
            onApplyFilter = { applied ->
                vm.setFilter(applied)
                dialogs.dismissFilter()
            },
            onClearFilter = vm::clearFilter,
            onSaveView = { name -> scope.launch { session.savedViews.save(newSavedView(ui, name)) } },
            onRenameView = { view, name ->
                scope.launch { session.savedViews.save(view.copy(name = name.trim())) }
            },
            onAddTag = vm::bulkAddTag,
            onRemoveTag = vm::bulkRemoveTag,
            renderFilter = !wide,
        )
    }
}

/** Desktop keyboard shortcuts for the library screen. Returns true when the event was handled. */
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
    return when {
        event.isCtrlPressed && event.key == Key.F -> {
            searchFocus.requestFocus()
            true
        }

        event.isCtrlPressed && event.key == Key.O -> {
            onImport()
            true
        }

        event.isCtrlPressed && event.key == Key.A -> {
            onSelectAll()
            true
        }

        event.key == Key.Slash -> {
            searchFocus.requestFocus()
            true
        }

        event.key == Key.Delete && selectionMode -> {
            onRequestTrash()
            true
        }

        event.key == Key.Escape -> {
            if (selectionMode) {
                onClearSelection()
            } else {
                onClearQuery()
            }
            true
        }

        else -> {
            false
        }
    }
}

@Composable
private fun LibraryContent(
    vm: LibraryViewModel,
    ui: LibraryUiState,
    session: LibrarySession,
    docs: List<dev.npn.core.app.DocListItem>?,
    tagColors: Map<String, String?>,
    reviewMode: ReviewMode,
    scanProgress: dev.npn.core.app.ScanProgressUi?,
    lastScan: dev.npn.core.index.ScanReport?,
    ocr: dev.npn.core.app.OcrStatus,
    snackbar: SnackbarHostState,
    onMenu: () -> Unit,
    onOpenDoc: (String) -> Unit,
    onTrashBanner: () -> Unit,
    onImport: () -> Unit,
    onScan: () -> Unit,
    scanAvailable: Boolean,
    onOpenFilter: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: () -> Unit,
) {
    val queryText by vm.queryText.collectAsStateWithLifecycle()
    val suggestions by vm.suggestions.collectAsStateWithLifecycle()
    val gridColumnWidth by vm.gridColumnWidth.collectAsStateWithLifecycle()
    val landscapeListColumns by vm.landscapeListColumns.collectAsStateWithLifecycle()
    val allDocIds = docs.orEmpty().map { it.doc.docId }
    val searchFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    // Give the shortcut host focus on entry; key events only reach it once something is focused.
    LaunchedEffect(Unit) { runCatching { contentFocus.requestFocus() } }
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
                    onSetReviewed = vm::bulkSetReviewed,
                    onRequestTrash = { confirmTrash = true },
                )
            } else {
                LibraryTopBar(
                    viewType = ui.viewType,
                    sort = ui.sort,
                    onMenu = onMenu,
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
            ActiveFilters(
                filter = ui.filter,
                onFilterChange = vm::setFilter,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Banners(
                scanProgress = scanProgress,
                lastScan = lastScan,
                ocr = ocr,
                dismissed = ui.bannerDismissed,
                onDismiss = vm::dismissBanner,
                onGoToTrash = onTrashBanner,
            )

            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = vm::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (docs == null) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else if (docs.isEmpty()) {
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
                                docs = docs,
                                content = session.content,
                                tagColors = tagColors,
                                ui = ui,
                                columnWidth = gridColumnWidth,
                                onColumnWidthChange = vm::setGridColumnWidth,
                                onOpen = onOpenDoc,
                                onToggleSelect = vm::toggleSelection,
                                onStartSelection = vm::startSelection,
                            )
                        }

                        ViewType.LIST -> {
                            DocList(
                                docs = docs,
                                content = session.content,
                                tagColors = tagColors,
                                reviewMode = reviewMode,
                                ui = ui,
                                landscapeColumns = landscapeListColumns,
                                onOpen = onOpenDoc,
                                onToggleSelect = vm::toggleSelection,
                                onStartSelection = vm::startSelection,
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
    docs: List<dev.npn.core.app.DocListItem>,
    content: dev.npn.core.app.DocContentFacade,
    tagColors: Map<String, String?>,
    reviewMode: ReviewMode,
    ui: LibraryUiState,
    landscapeColumns: Boolean,
    onOpen: (String) -> Unit,
    onToggleSelect: (String) -> Unit,
    onStartSelection: (String) -> Unit,
) {
    val row: @Composable (dev.npn.core.app.DocListItem) -> Unit = { item ->
        DocListRow(
            item = item,
            content = content,
            tagColors = tagColors,
            reviewMode = reviewMode,
            selectionMode = ui.selectionMode,
            selected = item.doc.docId in ui.selected,
            onClick = {
                if (ui.selectionMode) {
                    onToggleSelect(item.doc.docId)
                } else {
                    onOpen(item.doc.docId)
                }
            },
            onLongClick = { onStartSelection(item.doc.docId) },
            onToggleSelect = { onToggleSelect(item.doc.docId) },
        )
    }

    // Multi-column list when the option is on and the content area is landscape; otherwise the
    // classic single-column list. Two columns keeps each row wide enough to stay readable.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (landscapeColumns && isLandscape) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(docs, key = { it.doc.docId }) { item ->
                    Column {
                        row(item)
                        HorizontalDivider()
                    }
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(docs, key = { it.doc.docId }) { item ->
                    row(item)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun LibraryTopBar(
    viewType: ViewType,
    sort: dev.npn.core.app.LibrarySort,
    onMenu: () -> Unit,
    onOpenFilter: () -> Unit,
    onSetSort: (SortField, Boolean) -> Unit,
    onSetView: (ViewType) -> Unit,
    onRefresh: () -> Unit,
) {
    var sortMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(stringResource(Res.string.library_documents)) },
        navigationIcon = {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, contentDescription = stringResource(Res.string.common_menu))
            }
        },
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = stringResource(Res.string.library_refresh))
            }
            IconButton(onClick = onOpenFilter) {
                Icon(Icons.Filled.FilterList, contentDescription = stringResource(Res.string.library_filters))
            }
            // View switcher: one tap toggles list/grid; the icon shows the view it switches TO.
            val nextView = if (viewType == ViewType.LIST) ViewType.GRID else ViewType.LIST
            IconButton(onClick = { onSetView(nextView) }) {
                Icon(
                    imageVector =
                        when (nextView) {
                            ViewType.GRID -> Icons.Filled.GridView
                            ViewType.LIST -> Icons.AutoMirrored.Filled.ViewList
                        },
                    contentDescription =
                        stringResource(
                            Res.string.library_switch_view,
                            viewTypeLabel(nextView).lowercase(),
                        ),
                )
            }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = stringResource(Res.string.library_sort))
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    Text(
                        stringResource(Res.string.library_sort_by),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    SortField.entries.forEach { field ->
                        val active = sort.field == field
                        DropdownMenuItem(
                            text = { Text(sortLabel(field)) },
                            leadingIcon = { RadioButton(selected = active, onClick = null) },
                            trailingIcon = {
                                if (active) {
                                    Icon(
                                        imageVector =
                                            if (sort.ascending) {
                                                Icons.Filled.ArrowUpward
                                            } else {
                                                Icons.Filled.ArrowDownward
                                            },
                                        contentDescription =
                                            if (sort.ascending) {
                                                stringResource(Res.string.library_sort_ascending)
                                            } else {
                                                stringResource(Res.string.library_sort_descending)
                                            },
                                    )
                                }
                            },
                            onClick = {
                                if (active) {
                                    // Tapping the active field flips the direction; keep the menu
                                    // open so the arrow flip is visible.
                                    onSetSort(field, !sort.ascending)
                                } else {
                                    // Switching field keeps the current direction.
                                    onSetSort(field, sort.ascending)
                                    sortMenu = false
                                }
                            },
                        )
                    }
                }
            }
        },
    )
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
    onSetReviewed: (Boolean) -> Unit,
    onRequestTrash: () -> Unit,
) {
    var reviewMenu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(pluralStringResource(Res.plurals.library_selected_count, count, count)) },
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Clear, contentDescription = stringResource(Res.string.common_cancel))
            }
        },
        actions = {
            TextButton(onClick = onSelectAll, enabled = total > 0) {
                Text(stringResource(Res.string.common_select_all))
            }
            TextButton(onClick = onAddTag) { Text(stringResource(Res.string.common_add_tag)) }
            TextButton(onClick = onRemoveTag) { Text(stringResource(Res.string.library_remove_tag)) }
            // Manual reviewed marker only applies in the inbox-workflow mode.
            if (reviewMode == ReviewMode.INBOX_WORKFLOW) {
                Box {
                    TextButton(onClick = { reviewMenu = true }) { Text(stringResource(Res.string.library_review)) }
                    DropdownMenu(expanded = reviewMenu, onDismissRequest = { reviewMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.library_mark_reviewed)) },
                            onClick = {
                                onSetReviewed(true)
                                reviewMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.library_mark_needs_review)) },
                            onClick = {
                                onSetReviewed(false)
                                reviewMenu = false
                            },
                        )
                    }
                }
            }
            IconButton(onClick = onRequestTrash) {
                Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.library_trash))
            }
        },
    )
}

@Composable
private fun Banners(
    scanProgress: dev.npn.core.app.ScanProgressUi?,
    lastScan: dev.npn.core.index.ScanReport?,
    ocr: dev.npn.core.app.OcrStatus,
    dismissed: Boolean,
    onDismiss: () -> Unit,
    onGoToTrash: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        scanProgress?.let { ScanProgressBanner(it) }
        val conflicts = lastScan?.conflictFiles.orEmpty()
        val dupes = lastScan?.duplicates.orEmpty()
        if (!dismissed && (conflicts.isNotEmpty() || dupes.isNotEmpty())) {
            ScanIssuesBanner(conflicts.size, dupes.size, onGoToTrash, onDismiss)
        }
        if (ocr.pending > 0) OcrStatusIndicator(ocr)
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
private fun OcrStatusIndicator(ocr: dev.npn.core.app.OcrStatus) {
    Row(
        Modifier.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Filled.Refresh,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            if (ocr.running) {
                pluralStringResource(Res.plurals.library_ocr_pending_running, ocr.pending, ocr.pending)
            } else {
                pluralStringResource(Res.plurals.library_ocr_pending, ocr.pending, ocr.pending)
            },
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
        SortField.NAME -> stringResource(Res.string.library_sort_name)
        SortField.ADDED -> stringResource(Res.string.library_sort_added)
        SortField.DOC_DATE -> stringResource(Res.string.library_sort_doc_date)
        SortField.SIZE -> stringResource(Res.string.library_sort_size)
    }

@Composable
private fun viewTypeLabel(viewType: ViewType): String =
    when (viewType) {
        ViewType.LIST -> stringResource(Res.string.common_view_list)
        ViewType.GRID -> stringResource(Res.string.common_view_grid)
    }
