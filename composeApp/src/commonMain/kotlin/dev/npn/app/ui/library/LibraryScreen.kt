@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.BoxWithConstraintsLayout
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.ctrlScrollZoom
import dev.npn.app.ui.common.isLandscape
import dev.npn.app.ui.common.pinchToZoom
import dev.npn.app.ui.common.SearchField
import dev.npn.app.ui.common.platformFileToExternalSource
import dev.npn.app.ui.common.viewTypeLabel
import dev.npn.core.app.GridSizing
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SortField
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch

@Composable
fun LibraryScreen(
    session: LibrarySession,
    env: AppEnvironment,
    onOpenDoc: (docId: String, order: List<String>, searchQuery: String?) -> Unit,
    onTrash: () -> Unit,
    onTags: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm: LibraryViewModel = viewModel { LibraryViewModel(session, env.settings) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()

    // Opening a doc records the current sorted+filtered order so the detail screen can page between
    // documents; ids are stable so the order stays valid across navigation. The originating search
    // query is also passed through so the detail screen can highlight matching terms.
    val openDoc: (String) -> Unit = { docId ->
        onOpenDoc(docId, docs.map { it.doc.docId }, ui.filter.query)
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
    val drawerState = androidx.compose.material3.rememberDrawerState(
        androidx.compose.material3.DrawerValue.Closed,
    )

    // Surface one-shot VM messages (e.g. partial bulk-op failures) as snackbars.
    LaunchedEffect(vm, snackbar) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    val dialogs = rememberLibraryDialogState()

    val tagColors = remember(tags) { tags.associate { it.name to it.color } }

    val importPicker = rememberFilePickerLauncher(
        type = FileKitType.File("pdf"),
        mode = FileKitMode.Multiple(),
    ) { files ->
        if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
        val sources = files.map { platformFileToExternalSource(it) to it.name }
        vm.import(sources) { summary ->
            scope.launch { snackbar.showSnackbar(summary.message) }
        }
    }

    val scanner = dev.npn.app.platform.LocalDocumentScanner.current
    val onScan: () -> Unit = {
        scope.launch {
            val bytes = runCatching { scanner.scanToPdf() }.getOrNull()
            if (bytes != null) {
                vm.importScannedPdf(bytes) { outcome ->
                    scope.launch { snackbar.showSnackbar(outcome) }
                }
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

    val drawer: @Composable () -> Unit = {
        LibraryDrawerContent(
            reviewMode = reviewMode,
            reviewBadge = needsReview,
            savedViews = savedViews,
            onAllDocuments = {
                vm.clearFilter()
                scope.launch { drawerState.close() }
            },
            onReviewQueue = { vm.applyReviewPreset(); scope.launch { drawerState.close() } },
            onTrash = { scope.launch { drawerState.close() }; onTrash() },
            onTags = { scope.launch { drawerState.close() }; onTags() },
            onSettings = { scope.launch { drawerState.close() }; onSettings() },
            onSavedView = { view ->
                vm.applySavedView(view)
                scope.launch { drawerState.close() }
            },
            onSaveCurrentView = {
                scope.launch { drawerState.close() }
                dialogs.openSaveView()
            },
            onRenameSavedView = dialogs::openRename,
            // Thin pass-throughs to the session; run on the screen scope (quick fire-and-forget).
            onDeleteSavedView = { view -> scope.launch { session.savedViews.delete(view.id) } },
            onTogglePinSavedView = { view ->
                scope.launch { session.savedViews.save(view.copy(pinned = !view.pinned)) }
            },
        )
    }

    // Responsive: permanent drawer when wide (>840dp), modal otherwise.
    BoxWithConstraintsLayout(
        wideContent = {
            Row(Modifier.fillMaxSize()) {
                PermanentDrawerSheet(Modifier.padding(end = 0.dp)) { drawer() }
                Box(Modifier.weight(1f)) { content() }
            }
        },
        narrowContent = {
            ModalNavigationDrawer(
                drawerState = drawerState,
                drawerContent = { ModalDrawerSheet { drawer() } },
            ) { content() }
        },
    )

    LibraryDialogHost(
        state = dialogs,
        filter = ui.filter,
        selectedCount = ui.selected.size,
        tags = tags,
        correspondents = correspondents,
        docTypes = docTypes,
        onApplyFilter = vm::setFilter,
        onClearFilter = vm::clearFilter,
        onSaveView = { name -> scope.launch { session.savedViews.save(newSavedView(ui, name)) } },
        onRenameView = { view, name ->
            scope.launch { session.savedViews.save(view.copy(name = name.trim())) }
        },
        onAddTag = vm::bulkAddTag,
        onRemoveTag = vm::bulkRemoveTag,
    )
}

@Composable
private fun LibraryContent(
    vm: LibraryViewModel,
    ui: LibraryUiState,
    session: LibrarySession,
    docs: List<dev.npn.core.app.DocListItem>,
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
    Scaffold(
        topBar = {
            if (ui.selectionMode) {
                SelectionTopBar(
                    count = ui.selected.size,
                    reviewMode = reviewMode,
                    onClose = vm::clearSelection,
                    onAddTag = onAddTag,
                    onRemoveTag = onRemoveTag,
                    onSetReviewed = vm::bulkSetReviewed,
                    onTrash = vm::bulkTrash,
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            SearchField(
                value = queryText,
                onValueChange = vm::onQueryChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                suggestions = suggestions,
                onSuggestionSelected = vm::acceptSuggestion,
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
                if (docs.isEmpty()) {
                    if (ui.filter.isEmpty) {
                        EmptyState(
                            icon = Icons.Filled.Description,
                            title = "No documents yet",
                            subtitle = "Import a PDF to get started.",
                            actionLabel = "Import PDF",
                            onAction = onImport,
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Filled.Description,
                            title = "No matches",
                            subtitle = "Try adjusting your search or filters.",
                            actionLabel = "Clear filters",
                            onAction = vm::clearFilter,
                        )
                    }
                } else {
                    when (ui.viewType) {
                        ViewType.GRID -> DocGrid(
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
                        ViewType.LIST -> DocList(
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
                if (ui.selectionMode) onToggleSelect(item.doc.docId)
                else onOpen(item.doc.docId)
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
        title = { Text("Documents") },
        navigationIcon = {
            IconButton(onClick = onMenu) { Icon(Icons.Filled.Menu, contentDescription = "Menu") }
        },
        actions = {
            IconButton(onClick = onRefresh) {
                Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
            }
            IconButton(onClick = onOpenFilter) {
                Icon(Icons.Filled.FilterList, contentDescription = "Filters")
            }
            // View switcher: one tap toggles list/grid; the icon shows the view it switches TO.
            val nextView = if (viewType == ViewType.LIST) ViewType.GRID else ViewType.LIST
            IconButton(onClick = { onSetView(nextView) }) {
                Icon(
                    imageVector = when (nextView) {
                        ViewType.GRID -> Icons.Filled.GridView
                        ViewType.LIST -> Icons.AutoMirrored.Filled.ViewList
                    },
                    contentDescription = "Switch to ${viewTypeLabel(nextView).lowercase()} view",
                )
            }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.Sort, contentDescription = "Sort")
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    Text(
                        "Sort by",
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
                                        imageVector = if (sort.ascending) {
                                            Icons.Filled.ArrowUpward
                                        } else {
                                            Icons.Filled.ArrowDownward
                                        },
                                        contentDescription =
                                            if (sort.ascending) "Ascending" else "Descending",
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
    reviewMode: ReviewMode,
    onClose: () -> Unit,
    onAddTag: () -> Unit,
    onRemoveTag: () -> Unit,
    onSetReviewed: (Boolean) -> Unit,
    onTrash: () -> Unit,
) {
    var reviewMenu by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text("$count selected") },
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.Filled.Clear, contentDescription = "Cancel") }
        },
        actions = {
            TextButton(onClick = onAddTag) { Text("Add tag") }
            TextButton(onClick = onRemoveTag) { Text("Remove tag") }
            // Manual reviewed marker only applies in the inbox-workflow mode.
            if (reviewMode == ReviewMode.INBOX_WORKFLOW) {
                Box {
                    TextButton(onClick = { reviewMenu = true }) { Text("Review") }
                    DropdownMenu(expanded = reviewMenu, onDismissRequest = { reviewMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("Mark reviewed") },
                            onClick = { onSetReviewed(true); reviewMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text("Mark needs review") },
                            onClick = { onSetReviewed(false); reviewMenu = false },
                        )
                    }
                }
            }
            IconButton(onClick = { confirmTrash = true }) {
                Icon(Icons.Filled.Delete, contentDescription = "Trash")
            }
        },
    )
    if (confirmTrash) {
        dev.npn.app.ui.common.ConfirmDialog(
            title = "Trash documents",
            message = "Move $count document(s) to trash?",
            confirmLabel = "Trash",
            onConfirm = {
                onTrash()
                confirmTrash = false
            },
            onDismiss = { confirmTrash = false },
        )
    }
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
        if (scanProgress != null) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(
                        "${scanProgress.phase} (${scanProgress.done}/${scanProgress.total})",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    val fraction = if (scanProgress.total > 0) {
                        scanProgress.done.toFloat() / scanProgress.total
                    } else 0f
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                    )
                }
            }
        }

        val conflicts = lastScan?.conflictFiles.orEmpty()
        val dupes = lastScan?.duplicates.orEmpty()
        if (!dismissed && (conflicts.isNotEmpty() || dupes.isNotEmpty())) {
            Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Row(
                    Modifier.padding(12.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.Filled.Warning, contentDescription = null)
                    Text(
                        buildString {
                            if (conflicts.isNotEmpty()) append("${conflicts.size} conflict file(s). ")
                            if (dupes.isNotEmpty()) append("${dupes.size} duplicate(s).")
                        },
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = onGoToTrash) { Text("Review") }
                    TextButton(onClick = onDismiss) { Text("Dismiss") }
                }
            }
        }

        if (ocr.pending > 0) {
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
                    "OCR pending: ${ocr.pending}${if (ocr.running) " (running)" else ""}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
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
            text = { Text("Import") },
        )
        return
    }
    var expanded by remember { mutableStateOf(false) }
    Box {
        ExtendedFloatingActionButton(
            onClick = { expanded = true },
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("Add") },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Scan document") },
                onClick = { expanded = false; onScan() },
            )
            DropdownMenuItem(
                text = { Text("Import PDF") },
                onClick = { expanded = false; onImport() },
            )
        }
    }
}

private fun sortLabel(field: SortField): String = when (field) {
    SortField.NAME -> "Name"
    SortField.ADDED -> "Added"
    SortField.DOC_DATE -> "Doc date"
    SortField.SIZE -> "Size"
}

