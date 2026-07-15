@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
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
import dev.npn.app.ui.common.BoxWithConstraintsLayout
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.library.NameDialog
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.DocStatus
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.launch

@Composable
fun DocDetailScreen(
    docId: String,
    session: LibrarySession,
    onBack: () -> Unit,
) {
    val vm: DocDetailViewModel = viewModel(key = "detail-$docId") { DocDetailViewModel(docId, session) }
    val doc by vm.doc.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val correspondents by vm.correspondents.collectAsStateWithLifecycle()
    val docTypes by vm.docTypes.collectAsStateWithLifecycle()
    val tagColors by vm.tagColors.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var allTagNames by remember { mutableStateOf<List<String>>(emptyList()) }

    var showRename by remember { mutableStateOf(false) }
    var confirmTrash by remember { mutableStateOf(false) }
    var confirmBake by remember { mutableStateOf(false) }
    var showMetaSheet by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(docId) {
        allTagNames = runCatching { vm.allTagNames() }.getOrDefault(emptyList())
    }

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

    val reviewed = DocStatus.fromWire(current.doc.status) != DocStatus.INBOX

    Scaffold(
        topBar = {
            DetailTopBar(
                title = current.doc.title?.takeIf { it.isNotBlank() } ?: current.doc.fileName,
                reviewed = reviewed,
                baking = ui.baking,
                reindexing = ui.reindexing,
                onBack = onBack,
                onToggleReviewed = { if (reviewed) vm.markUnreviewed() else vm.markReviewed() },
                onRename = { showRename = true },
                onExport = {
                    saver.launch(
                        suggestedName = exportName(current),
                        defaultExtension = "pdf",
                    )
                },
                onBakeInPlace = { confirmBake = true },
                onReOcr = { vm.reOcr() },
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
                onCommitTitle = vm::setTitle,
                onCommitCorrespondent = vm::setCorrespondent,
                onCommitDocType = vm::setDocType,
                onCommitDocDate = vm::setDocDate,
                onSetStatus = vm::setStatus,
                onAddTag = vm::addTag,
                onRemoveTag = vm::removeTag,
                modifier = m,
            )
        }

        val viewer: @Composable (Modifier) -> Unit = { m ->
            PageViewer(
                pageIndex = ui.pageIndex,
                pageCount = ui.pageCount,
                zoom = ui.zoom,
                render = { p, maxDim -> vm.renderPage(p, maxDim) },
                onPrev = vm::prevPage,
                onNext = vm::nextPage,
                onZoomIn = vm::zoomIn,
                onZoomOut = vm::zoomOut,
                onSetZoom = vm::setZoom,
                modifier = m,
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
        NameDialog(
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
    reviewed: Boolean,
    baking: Boolean,
    reindexing: Boolean,
    onBack: () -> Unit,
    onToggleReviewed: () -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onBakeInPlace: () -> Unit,
    onReOcr: () -> Unit,
    onTrash: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
        },
        actions = {
            IconButton(onClick = onToggleReviewed) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = if (reviewed) "Mark unreviewed" else "Mark reviewed",
                    tint = if (reviewed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
    val base = (item.doc.title?.takeIf { it.isNotBlank() } ?: item.doc.fileName)
        .substringBeforeLast('.')
        .ifBlank { "document" }
    return base
}
