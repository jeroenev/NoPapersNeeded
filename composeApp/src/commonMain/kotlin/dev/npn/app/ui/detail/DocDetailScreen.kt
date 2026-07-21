@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.BoxWithConstraintsLayout
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.documentDisplayTitle
import dev.npn.app.ui.library.effectiveReviewed
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SplitOutcome
import dev.npn.core.model.ReviewMode
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.launch

@Composable
fun DocDetailScreen(
    docId: String,
    session: LibrarySession,
    browse: BrowseContext,
    onBack: () -> Unit,
    onNavigateToDoc: (String) -> Unit,
) {
    val searchQuery = browse.searchQuery
    val vm: DocDetailViewModel = viewModel(key = "detail-$docId-${searchQuery.orEmpty()}") {
        DocDetailViewModel(docId, session, searchQuery)
    }
    val doc by vm.doc.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val correspondents by vm.correspondents.collectAsStateWithLifecycle()
    val docTypes by vm.docTypes.collectAsStateWithLifecycle()
    val tagColors by vm.tagColors.collectAsStateWithLifecycle()
    val reviewMode by vm.reviewMode.collectAsStateWithLifecycle()
    val fieldKeys by vm.fieldKeys.collectAsStateWithLifecycle()
    val ocrText by vm.ocrText.collectAsStateWithLifecycle()
    val ocrVersions by vm.ocrVersions.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val noteEditor by vm.noteEditor.collectAsStateWithLifecycle()
    val highlightTerms = vm.highlightTerms

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    // Tag autocomplete: tagColors already carries every tag name reactively (from observeTags,
    // ordered by name), so derive the suggestion list from it instead of a separate one-shot load.
    val allTagNames = remember(tagColors) { tagColors.keys.toList() }

    // Surface one-shot VM messages (failed metadata writes etc.) as snackbars.
    LaunchedEffect(vm, snackbar) {
        vm.messages.collect { snackbar.showSnackbar(it) }
    }

    var showRename by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var confirmBake by remember { mutableStateOf(false) }
    var showSplit by remember { mutableStateOf(false) }
    var showMetaSheet by remember { mutableStateOf(false) }

    // Focus the viewer on entry (and when switching docs) so its keyboard shortcuts work on desktop.
    val viewerFocus = remember { FocusRequester() }
    LaunchedEffect(docId) { runCatching { viewerFocus.requestFocus() } }

    // Doc-to-doc paging within the browse order the library handed us.
    val prevDocId = browse.neighbour(docId, -1)
    val nextDocId = browse.neighbour(docId, 1)
    val browsePosition = browse.positionOf(docId)
    val goPrevDoc: (() -> Unit)? = prevDocId?.let { { onNavigateToDoc(it) } }
    val goNextDoc: (() -> Unit)? = nextDocId?.let { { onNavigateToDoc(it) } }

    // Export: bake a copy to a user-chosen destination.
    val saver = rememberFileSaverLauncher(
        dialogSettings = io.github.vinceglb.filekit.dialogs.FileKitDialogSettings.createDefault(),
    ) { file: PlatformFile? ->
        if (file != null) {
            scope.launch {
                val bytes = vm.bakedCopyBytes()
                if (bytes != null) {
                    runCatching { file.write(bytes) }
                        .onSuccess { snackbar.showSnackbar("Exported baked copy") }
                        .onFailure { snackbar.showSnackbar("Export failed: ${it.message}") }
                } else {
                    snackbar.showSnackbar("Could not bake a copy")
                }
            }
        }
    }

    val current = doc
    if (current == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Document") },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.Delete,
                    title = "Document unavailable",
                    subtitle = "It may have been trashed or moved.",
                )
            }
        }
        return
    }

    val reviewed = current.effectiveReviewed(reviewMode)
    // Manual reviewed toggle only makes sense in inbox-workflow mode (derived otherwise).
    val canToggleReviewed = reviewMode == ReviewMode.INBOX_WORKFLOW

    Scaffold(
        modifier = Modifier.onKeyEvent { event ->
            // Ctrl+Left/Right walks to the prev/next document (desktop).
            if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onKeyEvent false
            when (event.key) {
                Key.DirectionLeft -> goPrevDoc?.let { it(); true } ?: false
                Key.DirectionRight -> goNextDoc?.let { it(); true } ?: false
                else -> false
            }
        },
        topBar = {
            DetailTopBar(
                title = documentDisplayTitle(current.doc.title, current.doc.fileName),
                browsePosition = browsePosition,
                onPrevDoc = goPrevDoc,
                onNextDoc = goNextDoc,
                reviewed = reviewed,
                canToggleReviewed = canToggleReviewed,
                baking = ui.baking,
                reindexing = ui.reindexing,
                onBack = onBack,
                onToggleReviewed = { vm.setReviewed(!reviewed) },
                onRename = { showRename = true },
                onExport = {
                    saver.launch(
                        suggestedName = exportName(current),
                        defaultExtension = "pdf",
                    )
                },
                splitting = ui.splitting,
                canSplit = ui.pageCount > 1,
                onBakeInPlace = { confirmBake = true },
                onReOcr = { vm.reOcr() },
                onSplit = { showSplit = true },
                onTrash = { confirmTrash = true },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val panel: @Composable (Modifier) -> Unit = { m ->
            MetadataPanel(
                item = current,
                allTagNames = allTagNames,
                tagColors = tagColors,
                correspondents = correspondents,
                docTypes = docTypes,
                fieldKeys = fieldKeys,
                ocrText = ocrText,
                ocrVersions = ocrVersions,
                highlightTerms = highlightTerms,
                onSetOcrEngine = vm::setOcrEngine,
                onCommitTitle = vm::setTitle,
                noteEditor = noteEditor,
                onNoteChange = vm::updateNoteDraft,
                onSaveNote = vm::saveNote,
                onCommitCorrespondent = vm::setCorrespondent,
                onCommitDocType = vm::setDocType,
                onCommitDocDate = vm::setDocDate,
                onCommitField = { key, value -> vm.setField(key, value) },
                onRemoveField = vm::removeField,
                reviewMode = reviewMode,
                reviewed = reviewed,
                onSetReviewed = vm::setReviewed,
                onAddTag = vm::addTag,
                onRemoveTag = vm::removeTag,
                history = history,
                onLoadHistory = { vm.loadHistory() },
                onRefreshHistory = { vm.loadHistory(refresh = true) },
                modifier = m,
            )
        }

        val viewer: @Composable (Modifier) -> Unit = { m ->
            PageViewer(
                pageIndex = ui.pageIndex,
                pageCount = ui.pageCount,
                render = { p, maxDim -> vm.renderPage(p, maxDim) },
                onPageChange = vm::goToPage,
                modifier = m
                    .focusRequester(viewerFocus)
                    .focusable(),
            )
        }

        BoxWithConstraintsLayout(
            wideContent = {
                Row(Modifier.fillMaxSize().padding(padding)) {
                    viewer(Modifier.weight(1.6f).fillMaxSize())
                    Column(Modifier.weight(1f).fillMaxSize()) { panel(Modifier.fillMaxSize()) }
                }
            },
            narrowContent = {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    viewer(Modifier.weight(1f).fillMaxWidth())
                    Row(
                        Modifier.fillMaxWidth().padding(8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        androidx.compose.material3.OutlinedButton(onClick = { showMetaSheet = true }) {
                            Text("Details & metadata")
                        }
                    }
                }
            },
        )

        if (showMetaSheet) {
            ModalBottomSheet(
                onDismissRequest = { showMetaSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            ) {
                panel(Modifier.fillMaxWidth())
            }
        }
    }

    if (showRename) {
        TextInputDialog(
            title = "Rename file",
            label = "New file name",
            confirmLabel = "Rename",
            onConfirm = {
                vm.rename(it)
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }

    if (confirmTrash) {
        ConfirmDialog(
            title = "Trash document",
            message = "Move this document to trash? You can restore it later.",
            confirmLabel = "Trash",
            onConfirm = {
                confirmTrash = false
                vm.trash(onBack)
            },
            onDismiss = { confirmTrash = false },
        )
    }

    if (showSplit) {
        SplitDialog(
            pageCount = ui.pageCount,
            onConfirm = { segments ->
                showSplit = false
                vm.split(segments) { outcome ->
                    when (outcome) {
                        is SplitOutcome.Done -> onBack() // original is trashed; leave the screen
                        is SplitOutcome.Failed -> scope.launch {
                            snackbar.showSnackbar("Split failed: ${outcome.message}")
                        }
                    }
                }
            },
            onDismiss = { showSplit = false },
        )
    }

    if (confirmBake) {
        ConfirmDialog(
            title = "Bake metadata in place",
            message = "This rewrites the library file itself to embed tags and properties as XMP. " +
                "The file's content hash changes and the rewrite syncs to other devices. " +
                "Prefer Export for a portable copy that leaves the library untouched. Continue?",
            confirmLabel = "Bake in place",
            onConfirm = {
                confirmBake = false
                vm.bakeInPlace { ok ->
                    scope.launch {
                        snackbar.showSnackbar(if (ok) "Baked in place" else "Bake failed")
                    }
                }
            },
            onDismiss = { confirmBake = false },
        )
    }
}

@Composable
private fun DetailTopBar(
    title: String,
    browsePosition: Pair<Int, Int>?,
    onPrevDoc: (() -> Unit)?,
    onNextDoc: (() -> Unit)?,
    reviewed: Boolean,
    canToggleReviewed: Boolean,
    baking: Boolean,
    reindexing: Boolean,
    splitting: Boolean,
    canSplit: Boolean,
    onBack: () -> Unit,
    onToggleReviewed: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onBakeInPlace: () -> Unit,
    onReOcr: () -> Unit,
    onSplit: () -> Unit,
    onTrash: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1)
                if (browsePosition != null && browsePosition.second > 1) {
                    Text(
                        "${browsePosition.first} of ${browsePosition.second}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            // Prev/next document within the browse order.
            if (browsePosition != null && browsePosition.second > 1) {
                IconButton(onClick = { onPrevDoc?.invoke() }, enabled = onPrevDoc != null) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Previous document")
                }
                IconButton(onClick = { onNextDoc?.invoke() }, enabled = onNextDoc != null) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Next document")
                }
            }
            if (canToggleReviewed) {
                IconButton(onClick = onToggleReviewed) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = if (reviewed) "Mark unreviewed" else "Mark reviewed",
                        tint = if (reviewed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            IconButton(onClick = onExport) {
                Icon(Icons.Filled.Share, contentDescription = "Export baked copy")
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename file") },
                        onClick = { menu = false; onRename() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (reindexing) "Re-running OCR..." else "Re-run OCR") },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        enabled = !reindexing,
                        onClick = { menu = false; onReOcr() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (splitting) "Splitting..." else "Split document") },
                        leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                        enabled = canSplit && !splitting,
                        onClick = { menu = false; onSplit() },
                    )
                    DropdownMenuItem(
                        text = { Text(if (baking) "Baking..." else "Bake metadata in place") },
                        enabled = !baking,
                        onClick = { menu = false; onBakeInPlace() },
                    )
                    DropdownMenuItem(
                        text = { Text("Trash") },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = { menu = false; onTrash() },
                    )
                }
            }
        },
    )
}

private fun exportName(item: DocListItem): String {
    val base = documentDisplayTitle(item.doc.title, item.doc.fileName)
        .substringBeforeLast('.')
        .ifBlank { "document" }
    return base
}
