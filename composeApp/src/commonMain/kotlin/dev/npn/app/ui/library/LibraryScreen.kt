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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.di.AppEnvironment
import dev.npn.app.ui.common.BoxWithConstraintsLayout
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.isLandscape
import dev.npn.app.ui.common.SearchField
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

@Composable
fun LibraryScreen(
    session: LibrarySession,
    env: AppEnvironment,
    onOpenDoc: (String) -> Unit,
    onTrash: () -> Unit,
    onTags: () -> Unit,
    onSettings: () -> Unit,
) {
    val vm: LibraryViewModel = viewModel { LibraryViewModel(session, env.settings) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()
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

    var showFilter by remember { mutableStateOf(false) }
    var showSaveView by remember { mutableStateOf(false) }
    var showAddTag by remember { mutableStateOf(false) }
    var showRemoveTag by remember { mutableStateOf(false) }
    var renameView by remember { mutableStateOf<dev.npn.core.app.SavedView?>(null) }
    var allTagNames by remember { mutableStateOf<List<String>>(emptyList()) }

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
            onOpenDoc = onOpenDoc,
            onTrashBanner = onTrash,
            onImport = { importPicker.launch() },
            onScan = onScan,
            scanAvailable = scanner.available,
            onOpenFilter = { showFilter = true },
            onAddTag = {
                scope.launch { allTagNames = vm.allTagNames() }
                showAddTag = true
            },
            onRemoveTag = {
                scope.launch { allTagNames = vm.allTagNames() }
                showRemoveTag = true
            },
        )
    }

    val drawer: @Composable () -> Unit = {
        LibraryDrawerContent(
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
                showSaveView = true
            },
            onRenameSavedView = { renameView = it },
            onDeleteSavedView = { vm.deleteSavedView(it.id) },
            onTogglePinSavedView = { vm.togglePinSavedView(it) },
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

    if (showFilter) {
        FilterSheet(
            filter = ui.filter,
            tags = tags,
            correspondents = correspondents,
            docTypes = docTypes,
            onApply = {
                vm.setFilter(it)
                showFilter = false
            },
            onClearAll = { vm.clearFilter() },
            onDismiss = { showFilter = false },
        )
    }

    if (showSaveView) {
        NameDialog(
            title = "Save current view",
            label = "View name",
            onConfirm = {
                vm.saveCurrentView(it)
                showSaveView = false
            },
            onDismiss = { showSaveView = false },
        )
    }

    renameView?.let { view ->
        NameDialog(
            title = "Rename saved view",
            label = "View name",
            confirmLabel = "Rename",
            onConfirm = {
                vm.renameSavedView(view, it)
                renameView = null
            },
            onDismiss = { renameView = null },
        )
    }

    if (showAddTag) {
        TagPickerDialog(
            title = "Add tag to ${ui.selected.size} document(s)",
            allTagNames = allTagNames,
            confirmLabel = "Add",
            onConfirm = {
                vm.bulkAddTag(it)
                showAddTag = false
            },
            onDismiss = { showAddTag = false },
        )
    }

    if (showRemoveTag) {
        TagPickerDialog(
            title = "Remove tag from ${ui.selected.size} document(s)",
            allTagNames = allTagNames,
            confirmLabel = "Remove",
            onConfirm = {
                vm.bulkRemoveTag(it)
                showRemoveTag = false
            },
            onDismiss = { showRemoveTag = false },
        )
    }
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
                    EmptyState(
                        icon = Icons.AutoMirrored.Filled.List,
                        title = if (ui.filter.isEmpty) "No documents yet" else "No matches",
                        subtitle = if (ui.filter.isEmpty) {
                            "Import a PDF to get started."
                        } else {
                            "Try adjusting your search or filters."
                        },
                    )
                } else {
                    when (ui.viewType) {
                        ViewType.GRID -> DocGrid(
                            docs = docs,
                            content = session.content,
                            tagColors = tagColors,
                            ui = ui,
                            columnWidth = gridColumnWidth,
                            onOpen = onOpenDoc,
                            vm = vm,
                        )
                        ViewType.LIST -> DocList(
                            docs = docs,
                            content = session.content,
                            tagColors = tagColors,
                            reviewMode = reviewMode,
                            ui = ui,
                            landscapeColumns = landscapeListColumns,
                            onOpen = onOpenDoc,
                            vm = vm,
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
    vm: LibraryViewModel,
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
                if (ui.selectionMode) vm.toggleSelection(item.doc.docId)
                else onOpen(item.doc.docId)
            },
            onLongClick = { vm.startSelection(item.doc.docId) },
            onToggleSelect = { vm.toggleSelection(item.doc.docId) },
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
private fun DocGrid(
    docs: List<dev.npn.core.app.DocListItem>,
    content: dev.npn.core.app.DocContentFacade,
    tagColors: Map<String, String?>,
    ui: LibraryUiState,
    columnWidth: Int,
    onOpen: (String) -> Unit,
    vm: LibraryViewModel,
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(columnWidth.dp),
        modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(docs, key = { it.doc.docId }) { item ->
            DocGridCard(
                item = item,
                content = content,
                tagColors = tagColors,
                selectionMode = ui.selectionMode,
                selected = item.doc.docId in ui.selected,
                onClick = {
                    if (ui.selectionMode) vm.toggleSelection(item.doc.docId)
                    else onOpen(item.doc.docId)
                },
                onLongClick = { vm.startSelection(item.doc.docId) },
            )
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
                Icon(Icons.Filled.Info, contentDescription = "Filters")
            }
            // View switcher: LIST -> GRID.
            Box {
                var viewMenu by remember { mutableStateOf(false) }
                IconButton(onClick = { viewMenu = true }) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Change view")
                }
                DropdownMenu(expanded = viewMenu, onDismissRequest = { viewMenu = false }) {
                    ViewType.entries.forEach { v ->
                        DropdownMenuItem(
                            text = { Text(viewLabel(v)) },
                            trailingIcon = {
                                if (v == viewType) {
                                    Icon(Icons.Filled.Check, contentDescription = null)
                                }
                            },
                            onClick = {
                                onSetView(v)
                                viewMenu = false
                            },
                        )
                    }
                }
            }
            Box {
                IconButton(onClick = { sortMenu = true }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Sort")
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    SortField.entries.forEach { field ->
                        listOf(true, false).forEach { asc ->
                            DropdownMenuItem(
                                text = { Text("${sortLabel(field)} ${if (asc) "asc" else "desc"}") },
                                trailingIcon = {
                                    if (sort.field == field && sort.ascending == asc) {
                                        Icon(Icons.Filled.Check, contentDescription = null)
                                    }
                                },
                                onClick = {
                                    onSetSort(field, asc)
                                    sortMenu = false
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

private fun viewLabel(v: ViewType): String = when (v) {
    ViewType.LIST -> "List"
    ViewType.GRID -> "Grid"
}

private fun sortLabel(field: SortField): String = when (field) {
    SortField.NAME -> "Name"
    SortField.ADDED -> "Added"
    SortField.DOC_DATE -> "Doc date"
    SortField.SIZE -> "Size"
}


