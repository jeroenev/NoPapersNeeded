@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.platform.LocalDocumentScanner
import dev.npn.app.platform.platformFileToExternalSource
import dev.npn.app.ui.common.ActionMenuItems
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.FocusOnEntry
import dev.npn.app.ui.common.Handoff
import dev.npn.app.ui.common.KeyedViewModelStores
import dev.npn.app.ui.common.LoadingIndicator
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.SearchField
import dev.npn.app.ui.common.TopBarAction
import dev.npn.app.ui.common.ViewModelStoreLease
import dev.npn.app.ui.common.rememberSnackbarMessages
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SavedView
import dev.npn.core.app.ViewType
import dev.npn.core.model.reviewMarkerIsAuthoritative
import dev.npn.core.settings.SettingsRepository
import io.github.vinceglb.filekit.dialogs.FileKitMode
import io.github.vinceglb.filekit.dialogs.FileKitType
import io.github.vinceglb.filekit.dialogs.compose.rememberFilePickerLauncher
import io.github.vinceglb.filekit.name
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_add
import no_papers_needed.composeapp.generated.resources.library_clear_filters
import no_papers_needed.composeapp.generated.resources.library_import_pdf
import no_papers_needed.composeapp.generated.resources.library_no_documents_subtitle
import no_papers_needed.composeapp.generated.resources.library_no_documents_subtitle_scan
import no_papers_needed.composeapp.generated.resources.library_no_documents_title
import no_papers_needed.composeapp.generated.resources.library_no_matches_subtitle
import no_papers_needed.composeapp.generated.resources.library_no_matches_title
import no_papers_needed.composeapp.generated.resources.library_scan_document
import no_papers_needed.composeapp.generated.resources.reminders_upcoming
import no_papers_needed.composeapp.generated.resources.reminders_upcoming_count
import no_papers_needed.composeapp.generated.resources.triage_title
import no_papers_needed.composeapp.generated.resources.ux_review_count
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

/** Requests handed to the library by other screens. */
internal class LibraryHandoffs(
    val filter: Handoff<LibraryFilter>,
    val savedView: Handoff<SavedView>,
    val reviewQueue: Handoff<Unit>,
)

// The list gets the viewmodel only as its LibraryActions interface, the hand-down the conventions prescribe.
// Screen wiring: every callback names one destination, so folding two more into an object only moves the list.
@Suppress("ViewModelForwarding", "LongParameterList")
@Composable
internal fun LibraryScreen(
    session: LibrarySession,
    settings: SettingsRepository,
    onOpenDoc: (docId: String, order: List<String>, searchQuery: String?) -> Unit,
    onTrash: () -> Unit,
    /** Opens the processing queue page, behind the OCR / AI-enrichment progress banners. */
    onOpenQueue: () -> Unit,
    /**
     * Opens the AI-edit page, optionally scoped to the given documents (empty = no preselection);
     * null while no AI endpoint is configured.
     */
    onAiEdit: ((List<String>) -> Unit)?,
    handoffs: LibraryHandoffs,
    /** Opens one settings pane, where the "Getting started" card's steps are set. */
    onOpenSettings: (SettingsCategoryId) -> Unit,
    /** Opens the Upcoming reminders screen; the entry shows while reminders are on. */
    onOpenUpcoming: () -> Unit,
    /** Each document's viewmodel store, shared with the detail screen; the side panel holds the previewed one's. */
    documents: KeyedViewModelStores<String>,
    modifier: Modifier = Modifier,
    /** Unseen in-app reminder fallbacks (a desktop without a tray), shown as a badge on Upcoming. */
    upcomingBadge: Int = 0,
    vm: LibraryViewModel = viewModel { LibraryViewModel(session, settings, createSavedStateHandle()) },
    previewed: ViewModelStoreLease<String> = viewModel { ViewModelStoreLease(documents) },
    triageViewModels: ViewModelStoreLease<String> = rememberTriageLease(documents),
) {
    val state by vm.content.collectAsStateWithLifecycle()
    val triage by vm.triage.collectAsStateWithLifecycle()
    // The filter sheet's vocabulary, which the list itself does not show.
    val tags by vm.tags.collectAsStateWithLifecycle()
    val fieldFilters by vm.fieldFilters.collectAsStateWithLifecycle()
    val tagNames = remember(tags) { tags.map { it.name } }
    val ui = state.ui
    val docs = state.results?.items
    val wide = LocalWindowWidthClass.current.isWide
    val previewDocId by vm.preview.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarMessages(vm.messages)
    // A document's viewmodel is held only while a host on screen shows it: the side panel while it
    // previews the document, the triage mode while it is on that document.
    SideEffect {
        previewed.keepOnly(previewDocId.takeIf { wide && triage == null })
        triageViewModels.keepOnly(triage?.currentId)
    }
    val activeTriage = triage
    if (activeTriage != null) {
        TriageScreen(
            state = activeTriage,
            session = session,
            settings = settings,
            viewModels = triageViewModels.of(activeTriage.currentId),
            snackbar = snackbar,
            actions = vm,
            modifier = modifier,
        )
        return
    }

    // One handed over before the library showed applies at once; one handed over while it shows
    // (the "Show" of a drop's import message) as it arrives.
    DisposableEffect(Unit) {
        handoffs.filter.consume()?.let(vm::setFilter)
        handoffs.savedView.consume()?.let(vm::applySavedView)
        if (handoffs.reviewQueue.consume() != null) vm.applyReviewPreset()
        onDispose {}
    }
    LaunchedEffect(handoffs.filter) { handoffs.filter.arrivals.collect(vm::setFilter) }
    LaunchedEffect(handoffs.savedView) { handoffs.savedView.arrivals.collect(vm::applySavedView) }
    LaunchedEffect(handoffs.reviewQueue) { handoffs.reviewQueue.arrivals.collect { vm.applyReviewPreset() } }

    // Opening a doc records the current sorted+filtered order so the detail screen can page between
    // documents; ids are stable so the order stays valid across navigation. The originating search
    // query is also passed through so the detail screen can highlight matching terms. Remembered,
    // reading the current results through State, so the list's item wiring survives every result
    // or selection change.
    val currentDocs by rememberUpdatedState(docs)
    val currentQuery by rememberUpdatedState(ui.filter.query)
    val currentOnOpenDoc by rememberUpdatedState(onOpenDoc)
    val openDoc: (String) -> Unit =
        remember {
            { docId -> currentOnOpenDoc(docId, currentDocs.orEmpty().map { it.doc.docId }, currentQuery) }
        }

    val dialog by vm.dialog.collectAsStateWithLifecycle()

    // Measured for the side panel's width only; whether there is a side panel is the window's call.
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Wide layouts preview a tapped document in the side panel; compact ones open it directly.
        val onDocClick: (String) -> Unit =
            remember(wide) { { docId -> if (wide) vm.preview(docId) else openDoc(docId) } }

        val sidePanelWidth = (maxWidth * SIDE_PANEL_FRACTION).coerceIn(SidePanelMinWidth, SidePanelMaxWidth)
        val panel = Modifier.width(sidePanelWidth).fillMaxHeight()
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                LibraryContent(
                    state = state,
                    actions = vm,
                    content = session.content,
                    snackbar = snackbar,
                    onOpenDoc = onDocClick,
                    exits =
                        remember(onOpenQueue, onTrash, onOpenSettings) {
                            LibraryExits(queue = onOpenQueue, trash = onTrash, settings = onOpenSettings)
                        },
                    onAiEdit = onAiEdit,
                    onOpenUpcoming = onOpenUpcoming,
                    upcomingBadge = upcomingBadge,
                    onDialog = vm::openDialog,
                )
            }
            // Wide layouts give the side panel to the filter editor when it is open, else to the
            // tapped document's preview, which vanishes when its document leaves the current result
            // set (trashed, filtered out, library rescan). Compact layouts edit the filter in a sheet.
            val items = docs.orEmpty()
            val previewItem = items.firstOrNull { it.doc.docId == previewDocId }.takeIf { wide }
            when {
                dialog == LibraryDialog.Filter -> {
                    val editor = rememberFilterEditorState(ui.filter)
                    val apply = { applied: LibraryFilter ->
                        vm.setFilter(applied)
                        vm.openDialog(null)
                    }
                    if (wide) {
                        FilterPanel(
                            editor = editor,
                            tags = tags,
                            fields = fieldFilters,
                            reviewMode = state.reviewMode,
                            onApply = apply,
                            onDismiss = { vm.openDialog(null) },
                            fillHeight = true,
                            modifier = panel,
                        )
                    } else {
                        FilterSheet(
                            editor = editor,
                            tags = tags,
                            fields = fieldFilters,
                            reviewMode = state.reviewMode,
                            onApply = apply,
                            onDismiss = { vm.openDialog(null) },
                        )
                    }
                }

                previewItem != null -> {
                    // By id, not by value: indexOf would run a full structural comparison per
                    // row on every recomposition of this branch.
                    val nextId = items.getOrNull(items.indexOfFirst { it.doc.docId == previewDocId } + 1)?.doc?.docId
                    DocPreviewPanel(
                        item = previewItem,
                        viewModels = previewed.of(previewItem.doc.docId),
                        session = session,
                        settings = settings,
                        snackbar = snackbar,
                        onClose = { vm.preview(null) },
                        onExpand = { openDoc(previewItem.doc.docId) },
                        onReviewNext = { if (vm.preview.value == previewItem.doc.docId) vm.preview(nextId) },
                        hasNext = nextId != null,
                        searchQuery = ui.filter.query,
                        modifier = panel,
                    )
                }
            }
        }

        LibraryDialogHost(
            dialog = dialog,
            selectedCount = ui.selected.size,
            // Removing a tag the selection does not carry is a no-op, so that picker offers only theirs,
            // read only while it is open.
            tagOptions =
                if (dialog == LibraryDialog.RemoveTag) {
                    vm.selectionTags.collectAsStateWithLifecycle().value
                } else {
                    tagNames
                },
            onSaveView = vm::saveCurrentView,
            onRenameView = vm::renameSavedView,
            onAddTag = vm::bulkAddTag,
            onRemoveTag = vm::bulkRemoveTag,
            onTrash = vm::bulkTrash,
            onDismiss = { vm.openDialog(null) },
        )
    }
}

/** The other screens the library's banners and its "Getting started" card lead to. */
private class LibraryExits(
    val queue: () -> Unit,
    val trash: () -> Unit,
    val settings: (SettingsCategoryId) -> Unit,
)

/**
 * The library list with its bars, search, chips and banners: a renderer of [state] that acts
 * through [actions]. The screen hands over the rest of what the list cannot know: the snackbar it
 * shares with the preview panel, where navigation goes ([onOpenDoc], [exits]), and [onDialog] for
 * the dialogs the screen hosts. [onAiEdit] is null while no AI endpoint is configured.
 */
@Suppress("LongParameterList") // The screen's own wiring: state, actions, and one callback per exit.
@Composable
private fun LibraryContent(
    state: LibraryContentState,
    actions: LibraryActions,
    content: DocContentFacade,
    snackbar: SnackbarHostState,
    onOpenDoc: (String) -> Unit,
    exits: LibraryExits,
    onAiEdit: ((selection: List<String>) -> Unit)?,
    onOpenUpcoming: () -> Unit,
    upcomingBadge: Int,
    onDialog: (LibraryDialog) -> Unit,
) {
    val ui = state.ui
    val gettingStarted = state.gettingStarted
    // A local, not the property: the empty and loading branches below smart-cast it.
    val results = state.results
    val reviewMode = state.reviewMode
    // Wide layouts show more top-bar actions as direct icons before spilling them into the overflow
    // menu, and preview a tapped document in the side panel, so its click label reads "Preview"
    // where compact layouts open it and read "Open".
    val wide = LocalWindowWidthClass.current.isWide
    val allDocIds = remember(results) { results?.items.orEmpty().map { it.doc.docId } }
    // Read through State by the item wiring, which a result change must not rebuild.
    val currentDocIds by rememberUpdatedState(allDocIds)
    // The view menu's grid size items, answered by the grid on screen.
    val gridZoom = remember { GridZoom() }

    val importPicker =
        rememberFilePickerLauncher(
            type = FileKitType.File("pdf"),
            mode = FileKitMode.Multiple(),
        ) { files ->
            if (files.isNullOrEmpty()) return@rememberFilePickerLauncher
            actions.import(files.map { platformFileToExternalSource(it) to it.name })
        }
    val onImport: () -> Unit = { importPicker.launch() }
    // Null without a scanner (desktop); the scan's result comes back to the viewmodel, even when
    // this activity was recreated while the scanner was open.
    val scan = LocalDocumentScanner.current?.rememberLauncher(actions::importScan)

    // Remembered, like the item wiring built from them below, so they outlive a state change.
    val currentOnDialog by rememberUpdatedState(onDialog)
    val onAddTag: () -> Unit = remember { { currentOnDialog(LibraryDialog.AddTag) } }
    // Only the tags the selection actually carries: removing any other is a no-op.
    val onRemoveTag: () -> Unit = remember { { currentOnDialog(LibraryDialog.RemoveTag) } }
    val onRequestTrash: () -> Unit = remember { { currentOnDialog(LibraryDialog.ConfirmTrash) } }
    // Hand the selection to the AI-edit page and leave selection mode behind.
    val aiEditSelection: (() -> Unit)? =
        onAiEdit?.let { open ->
            {
                val selected = ui.selected.toList()
                actions.clearSelection()
                open(selected)
            }
        }
    val searchFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    var searchFocused by remember { mutableStateOf(false) }
    // The right-click menu's tag and trash actions act on the selection, so they first pull the
    // clicked document into it (replacing the selection when it was not part of it). Remembered,
    // reading the live selection through State, so a selection change leaves every item's wiring
    // as it was.
    val selectedNow by rememberUpdatedState(ui.selected)
    val wiring =
        remember(
            content,
            state.tagColors,
            reviewMode,
            wide,
            actions,
            onOpenDoc,
            onAddTag,
            onRemoveTag,
            onRequestTrash,
        ) {
            fun onSelection(action: () -> Unit): (String) -> Unit =
                { docId ->
                    if (docId !in selectedNow) actions.startSelection(docId)
                    action()
                }
            DocItemWiring(
                content = content,
                tagColors = state.tagColors,
                reviewMode = reviewMode,
                previewOnClick = wide,
                onOpen = onOpenDoc,
                onToggleSelect = actions::toggleSelection,
                onSelectRange = { docId -> actions.selectRange(docId, currentDocIds) },
                onStartSelection = actions::startSelection,
                onAddTag = onSelection(onAddTag),
                onRemoveTag = onSelection(onRemoveTag),
                onTrash = onSelection(onRequestTrash),
            )
        }
    // Give the shortcut host focus on entry; key events only reach it once something is focused.
    FocusOnEntry(contentFocus)
    Scaffold(
        topBar = {
            if (ui.selectionMode) {
                LibrarySelectionTopBar(
                    count = ui.selected.size,
                    allDocIds = allDocIds,
                    reviewMode = reviewMode,
                    wide = wide,
                    actions = actions,
                    onAddTag = onAddTag,
                    onRemoveTag = onRemoveTag,
                    onAiEdit = aiEditSelection,
                    onRequestTrash = onRequestTrash,
                )
            } else {
                LibraryTopBar(
                    viewType = ui.viewType,
                    gridZoom = gridZoom,
                    extras = {
                        LibraryViewsMenuButton(
                            reviewMode = reviewMode,
                            savedViews = state.savedViews,
                            actions = actions,
                            onSaveCurrentView = { onDialog(LibraryDialog.SaveView) },
                            onRenameSavedView = { onDialog(LibraryDialog.RenameView(it.id, it.name)) },
                        )
                    },
                    onOpenFilter = { onDialog(LibraryDialog.Filter) },
                    onSetView = actions::setViewType,
                    onSelectAll = { actions.selectAll(allDocIds) }.takeIf { allDocIds.isNotEmpty() },
                    onRefresh = actions::refresh,
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
                .libraryKeys(
                    searchFocused = searchFocused,
                    selectionMode = ui.selectionMode,
                    onFocusSearch = searchFocus::requestFocus,
                    onImport = onImport,
                    onSelectAll = { if (allDocIds.isNotEmpty()) actions.selectAll(allDocIds) },
                    onRequestTrash = onRequestTrash,
                    onClearSelection = actions::clearSelection,
                    onClearQuery = { actions.onQueryChange("") },
                ),
        ) {
            SearchField(
                value = state.queryText,
                onValueChange = actions::onQueryChange,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                suggestions = state.suggestions,
                onPickSuggestion = actions::acceptSuggestion,
                focusRequester = searchFocus,
                onFocusChange = { searchFocused = it },
            )
            if (reviewMode.reviewMarkerIsAuthoritative && !ui.selectionMode) {
                FlowRow(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    itemVerticalAlignment = Alignment.CenterVertically,
                ) {
                    if (state.reviewCount > 0) {
                        ReviewChip(count = state.reviewCount, filter = ui.filter, actions = actions)
                    }
                    FilledTonalButton(
                        onClick = actions::startTriage,
                        enabled = !results?.items.isNullOrEmpty(),
                    ) {
                        Text(stringResource(Res.string.triage_title))
                    }
                }
            }
            UpcomingEntry(
                state = state,
                unseen = upcomingBadge,
                selectionMode = ui.selectionMode,
                onClick = onOpenUpcoming,
            )
            ActiveFilters(
                filter = ui.filter,
                fields = state.fields,
                onFilterChange = actions::setFilter,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Banners(
                banners = state.banners,
                dismissed = ui.scanIssuesDismissed(state.banners.lastScan),
                onDismiss = actions::dismissBanner,
                onGoToTrash = exits.trash,
                onShowDocuments = { docIds -> actions.setFilter(LibraryFilter(docIds = docIds)) },
                onOpenQueue = exits.queue,
            )
            if (gettingStarted != null && !ui.selectionMode) {
                GettingStartedCard(
                    steps = gettingStarted,
                    // The first document shown: tags are added on a document.
                    onAddTag = { allDocIds.firstOrNull()?.let(onOpenDoc) },
                    onTrySearch = searchFocus::requestFocus,
                    onOpenSettings = exits.settings,
                    onDismiss = actions::dismissGettingStarted,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            // A concise "N documents · sorted by …" line sits just above the results, in the fixed
            // band so it never scrolls away; it is the sort control too. Only shown once the first
            // result set has arrived and is non-empty (the empty states carry their own copy).
            results?.items?.takeIf { it.isNotEmpty() }?.let { items ->
                SortSummary(
                    count = items.size,
                    sort = ui.sort,
                    sortableFields = state.sortableFields,
                    onSetSort = actions::setSort,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }

            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = actions::refresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                if (results == null) {
                    LoadingIndicator()
                } else if (results.items.isEmpty()) {
                    if (ui.filter.isEmpty) {
                        EmptyState(
                            icon = Icons.Filled.Description,
                            title = stringResource(Res.string.library_no_documents_title),
                            // Say every way in that this platform has, not just the import button:
                            // dragging files, a watched folder and scanning are easy to miss.
                            subtitle =
                                stringResource(
                                    if (scan != null) {
                                        Res.string.library_no_documents_subtitle_scan
                                    } else {
                                        Res.string.library_no_documents_subtitle
                                    },
                                ),
                            actionLabel = stringResource(Res.string.library_import_pdf),
                            onAction = onImport,
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Filled.Description,
                            title = stringResource(Res.string.library_no_matches_title),
                            subtitle = stringResource(Res.string.library_no_matches_subtitle),
                            actionLabel = stringResource(Res.string.library_clear_filters),
                            onAction = actions::clearFilter,
                        )
                    }
                } else {
                    when (ui.viewType) {
                        ViewType.GRID -> {
                            DocGrid(
                                results = results,
                                wiring = wiring,
                                selectionMode = ui.selectionMode,
                                selected = ui.selected,
                                columnWidth = state.gridColumnWidth,
                                onColumnWidthChange = actions::setGridColumnWidth,
                                zoom = gridZoom,
                            )
                        }

                        ViewType.LIST -> {
                            DocList(
                                results = results,
                                wiring = wiring,
                                selectionMode = ui.selectionMode,
                                selected = ui.selected,
                                landscapeColumns = state.landscapeListColumns,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * A visible way into the Inbox, as a selectable filter affordance rather than a plain counter. It
 * sits below the search field, in the same band as ActiveFilters (which already grows and shrinks),
 * so appearing/disappearing never shifts the search box. The caller shows it only when there is
 * something to review, so a cleared inbox does not leave a permanent "0" in the way. Its selected
 * state mirrors whether the INBOX_WORKFLOW preset (unreviewedOrUntagged) is the active [filter], so
 * it reads as a filter, not a count.
 */
@Composable
private fun ReviewChip(
    count: Int,
    filter: LibraryFilter,
    actions: LibraryActions,
) {
    val reviewFilterActive = filter.unreviewedOrUntagged
    FilterChip(
        selected = reviewFilterActive,
        // Re-tapping drops only this facet, the normal behaviour of a selected filter chip. Not
        // clearFilter(): that also wipes the search box and every other facet the user narrowed the
        // queue with after applying the preset.
        onClick = {
            if (reviewFilterActive) {
                actions.setFilter(filter.copy(unreviewedOrUntagged = false))
            } else {
                actions.applyReviewPreset()
            }
        },
        label = { Text(stringResource(Res.string.ux_review_count, count)) },
        leadingIcon = {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        },
    )
}

/** The Upcoming entry, shown only when reminders are on and the list is not in selection mode. */
@Composable
private fun UpcomingEntry(
    state: LibraryContentState,
    unseen: Int,
    selectionMode: Boolean,
    onClick: () -> Unit,
) {
    if (!state.remindersEnabled || selectionMode) return
    UpcomingChip(count = state.upcomingCount, unseen = unseen, onClick = onClick)
}

/**
 * The way into Upcoming, beside the Inbox chip: the count is what is due now, and a badge marks
 * an unseen in-app reminder.
 */
@Composable
private fun UpcomingChip(
    count: Int,
    unseen: Int,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = {
            Text(
                if (count > 0) {
                    stringResource(Res.string.reminders_upcoming_count, count)
                } else {
                    stringResource(Res.string.reminders_upcoming)
                },
            )
        },
        leadingIcon = {
            BadgedBox(
                badge = {
                    if (unseen > 0) Badge { Text(unseen.toString()) }
                },
            ) {
                Icon(
                    Icons.Outlined.Event,
                    contentDescription = null,
                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                )
            }
        },
        modifier = Modifier.padding(horizontal = 16.dp),
    )
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
            text = { Text(stringResource(Res.string.library_import_pdf)) },
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
            ActionMenuItems(
                actions =
                    listOf(
                        TopBarAction(
                            Icons.Filled.DocumentScanner,
                            stringResource(Res.string.library_scan_document),
                            onScan,
                        ),
                        TopBarAction(Icons.Filled.UploadFile, stringResource(Res.string.library_import_pdf), onImport),
                    ),
                onDismiss = { expanded = false },
            )
        }
    }
}

@Composable
internal fun rememberTriageLease(documents: KeyedViewModelStores<String>): ViewModelStoreLease<String> =
    viewModel(key = "triage") { ViewModelStoreLease(documents) }
