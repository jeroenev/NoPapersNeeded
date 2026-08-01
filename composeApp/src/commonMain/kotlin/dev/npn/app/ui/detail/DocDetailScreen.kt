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
import androidx.compose.material.icons.filled.Save
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
import dev.npn.app.ui.common.DesktopBridge
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TextInputDialog
import dev.npn.app.ui.common.documentDisplayTitle
import dev.npn.app.ui.common.sharePdfBytes
import dev.npn.app.ui.common.supportsSharing
import dev.npn.app.ui.library.effectiveReviewed
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SplitOutcome
import dev.npn.core.model.ReviewMode
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.common_unknown_error
import no_papers_needed.composeapp.generated.resources.detail_bake_failed
import no_papers_needed.composeapp.generated.resources.detail_bake_in_place
import no_papers_needed.composeapp.generated.resources.detail_bake_metadata_in_place
import no_papers_needed.composeapp.generated.resources.detail_bake_prompt
import no_papers_needed.composeapp.generated.resources.detail_baked_in_place
import no_papers_needed.composeapp.generated.resources.detail_baking
import no_papers_needed.composeapp.generated.resources.detail_could_not_bake_copy
import no_papers_needed.composeapp.generated.resources.detail_details_metadata
import no_papers_needed.composeapp.generated.resources.detail_document
import no_papers_needed.composeapp.generated.resources.detail_document_position
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable_hint
import no_papers_needed.composeapp.generated.resources.detail_export_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_export_failed
import no_papers_needed.composeapp.generated.resources.detail_exported_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_mark_reviewed
import no_papers_needed.composeapp.generated.resources.detail_mark_unreviewed
import no_papers_needed.composeapp.generated.resources.detail_more_actions
import no_papers_needed.composeapp.generated.resources.detail_new_file_name
import no_papers_needed.composeapp.generated.resources.detail_next_document
import no_papers_needed.composeapp.generated.resources.detail_previous_document
import no_papers_needed.composeapp.generated.resources.detail_rename
import no_papers_needed.composeapp.generated.resources.detail_rename_file
import no_papers_needed.composeapp.generated.resources.detail_rerun_ocr
import no_papers_needed.composeapp.generated.resources.detail_rerunning_ocr
import no_papers_needed.composeapp.generated.resources.detail_share_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_share_failed
import no_papers_needed.composeapp.generated.resources.detail_shared_baked_copy
import no_papers_needed.composeapp.generated.resources.detail_split_document
import no_papers_needed.composeapp.generated.resources.detail_split_failed
import no_papers_needed.composeapp.generated.resources.detail_splitting
import no_papers_needed.composeapp.generated.resources.detail_trash
import no_papers_needed.composeapp.generated.resources.detail_trash_document
import no_papers_needed.composeapp.generated.resources.detail_trash_prompt
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

@Composable
@Suppress("CyclomaticComplexMethod", "LongMethod")
fun DocDetailScreen(
    docId: String,
    session: LibrarySession,
    browse: BrowseContext,
    onBack: () -> Unit,
    onNavigateToDoc: (String) -> Unit,
) {
    val searchQuery = browse.searchQuery
    val vm: DocDetailViewModel =
        viewModel(key = "detail-$docId-${searchQuery.orEmpty()}") {
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
    val aiTags by vm.aiTags.collectAsStateWithLifecycle()
    val highlightTerms = vm.highlightTerms

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)
    // Tag autocomplete: tagColors already carries every tag name reactively (from observeTags,
    // ordered by name), so derive the suggestion list from it instead of a separate one-shot load.
    val allTagNames = remember(tagColors) { tagColors.keys.toList() }

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
    val exportedBakedCopy = stringResource(Res.string.detail_exported_baked_copy)
    val sharedBakedCopy = stringResource(Res.string.detail_shared_baked_copy)
    val couldNotBakeCopy = stringResource(Res.string.detail_could_not_bake_copy)
    val unknownError = stringResource(Res.string.common_unknown_error)
    val saver =
        rememberFileSaverLauncher(
            dialogSettings =
                io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
                    .createDefault(),
        ) { file: PlatformFile? ->
            if (file != null) {
                scope.launch {
                    val bytes = vm.bakedCopyBytes()
                    if (bytes != null) {
                        runCatching { file.write(bytes) }
                            .onSuccess { snackbar.showSnackbar(exportedBakedCopy) }
                            .onFailure {
                                snackbar.showSnackbar(
                                    getString(Res.string.detail_export_failed, it.message ?: unknownError),
                                )
                            }
                    } else {
                        snackbar.showSnackbar(couldNotBakeCopy)
                    }
                }
            }
        }

    val current = doc
    if (current == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(Res.string.detail_document)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(Res.string.common_back),
                            )
                        }
                    },
                )
            },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.Delete,
                    title = stringResource(Res.string.detail_document_unavailable),
                    subtitle = stringResource(Res.string.detail_document_unavailable_hint),
                )
            }
        }
        return
    }

    val reviewed = current.effectiveReviewed(reviewMode)
    // Manual reviewed toggle only makes sense in inbox-workflow mode (derived otherwise).
    val canToggleReviewed = reviewMode == ReviewMode.INBOX_WORKFLOW

    // Reflect the open document in the native window title (desktop); inert elsewhere.
    val displayTitle = documentDisplayTitle(current.doc.title, current.doc.fileName)
    LaunchedEffect(displayTitle) { DesktopBridge.windowTitle.value = displayTitle }

    Scaffold(
        modifier =
            Modifier.onKeyEvent { event ->
                // Ctrl+Left/Right walks to the prev/next document (desktop).
                if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return@onKeyEvent false
                when (event.key) {
                    Key.DirectionLeft -> {
                        goPrevDoc?.let {
                            it()
                            true
                        } ?: false
                    }

                    Key.DirectionRight -> {
                        goNextDoc?.let {
                            it()
                            true
                        } ?: false
                    }

                    else -> {
                        false
                    }
                }
            },
        topBar = {
            DetailTopBar(
                title = displayTitle,
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
                sharingSupported = supportsSharing(),
                onShare = {
                    scope.launch {
                        val bytes = vm.bakedCopyBytes()
                        if (bytes != null) {
                            runCatching { sharePdfBytes(exportName(current) + ".pdf", bytes) }
                                .onSuccess { snackbar.showSnackbar(sharedBakedCopy) }
                                .onFailure {
                                    snackbar.showSnackbar(
                                        getString(Res.string.detail_share_failed, it.message ?: unknownError),
                                    )
                                }
                        } else {
                            snackbar.showSnackbar(couldNotBakeCopy)
                        }
                    }
                },
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
                aiTags = aiTags,
                onSuggestAiTags = vm::suggestAiTags,
                onAcceptAiTag = vm::acceptAiTag,
                onDismissAiTag = vm::dismissAiTag,
                onClearAiTagSuggestions = vm::clearAiTagSuggestions,
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
                modifier =
                    m
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
                            Text(stringResource(Res.string.detail_details_metadata))
                        }
                    }
                }
            },
        )

        if (showMetaSheet) {
            // ModalBottomSheet already draws the M3 drag handle; partial expansion lets it peek.
            ModalBottomSheet(
                onDismissRequest = { showMetaSheet = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            ) {
                panel(Modifier.fillMaxWidth())
            }
        }
    }

    if (showRename) {
        TextInputDialog(
            title = stringResource(Res.string.detail_rename_file),
            label = stringResource(Res.string.detail_new_file_name),
            confirmLabel = stringResource(Res.string.detail_rename),
            onConfirm = {
                vm.rename(it)
                showRename = false
            },
            onDismiss = { showRename = false },
        )
    }

    if (confirmTrash) {
        ConfirmDialog(
            title = stringResource(Res.string.detail_trash_document),
            message = stringResource(Res.string.detail_trash_prompt),
            confirmLabel = stringResource(Res.string.detail_trash),
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
                        is SplitOutcome.Done -> {
                            onBack()
                        }

                        // original is trashed; leave the screen
                        is SplitOutcome.Failed -> {
                            scope.launch {
                                snackbar.showSnackbar(
                                    getString(
                                        Res.string.detail_split_failed,
                                        outcome.message.ifBlank { unknownError },
                                    ),
                                )
                            }
                        }
                    }
                }
            },
            onDismiss = { showSplit = false },
        )
    }

    if (confirmBake) {
        ConfirmDialog(
            title = stringResource(Res.string.detail_bake_metadata_in_place),
            message = stringResource(Res.string.detail_bake_prompt),
            confirmLabel = stringResource(Res.string.detail_bake_in_place),
            onConfirm = {
                confirmBake = false
                vm.bakeInPlace { ok ->
                    scope.launch {
                        snackbar.showSnackbar(
                            if (ok) {
                                getString(Res.string.detail_baked_in_place)
                            } else {
                                getString(Res.string.detail_bake_failed)
                            },
                        )
                    }
                }
            },
            onDismiss = { confirmBake = false },
        )
    }
}

@Composable
@Suppress("LongMethod")
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
    sharingSupported: Boolean,
    onShare: () -> Unit,
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
                        stringResource(
                            Res.string.detail_document_position,
                            browsePosition.first,
                            browsePosition.second,
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(Res.string.common_back),
                )
            }
        },
        actions = {
            // Prev/next document within the browse order.
            if (browsePosition != null && browsePosition.second > 1) {
                IconButton(onClick = { onPrevDoc?.invoke() }, enabled = onPrevDoc != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = stringResource(Res.string.detail_previous_document),
                    )
                }
                IconButton(onClick = { onNextDoc?.invoke() }, enabled = onNextDoc != null) {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = stringResource(Res.string.detail_next_document),
                    )
                }
            }
            if (canToggleReviewed) {
                IconButton(onClick = onToggleReviewed) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription =
                            stringResource(
                                if (reviewed) {
                                    Res.string.detail_mark_unreviewed
                                } else {
                                    Res.string.detail_mark_reviewed
                                },
                            ),
                        tint =
                            if (reviewed) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
            if (sharingSupported) {
                IconButton(onClick = onShare) {
                    Icon(
                        Icons.Filled.Share,
                        contentDescription = stringResource(Res.string.detail_share_baked_copy),
                    )
                }
            } else {
                IconButton(onClick = onExport) {
                    Icon(
                        Icons.Filled.Save,
                        contentDescription = stringResource(Res.string.detail_export_baked_copy),
                    )
                }
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(
                        Icons.Filled.MoreVert,
                        contentDescription = stringResource(Res.string.detail_more_actions),
                    )
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.detail_rename_file)) },
                        onClick = {
                            menu = false
                            onRename()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (reindexing) Res.string.detail_rerunning_ocr else Res.string.detail_rerun_ocr,
                                ),
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.Refresh, contentDescription = null) },
                        enabled = !reindexing,
                        onClick = {
                            menu = false
                            onReOcr()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (splitting) Res.string.detail_splitting else Res.string.detail_split_document,
                                ),
                            )
                        },
                        leadingIcon = { Icon(Icons.Filled.ContentCut, contentDescription = null) },
                        enabled = canSplit && !splitting,
                        onClick = {
                            menu = false
                            onSplit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(
                                    if (baking) {
                                        Res.string.detail_baking
                                    } else {
                                        Res.string.detail_bake_metadata_in_place
                                    },
                                ),
                            )
                        },
                        enabled = !baking,
                        onClick = {
                            menu = false
                            onBakeInPlace()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.detail_trash)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                        onClick = {
                            menu = false
                            onTrash()
                        },
                    )
                }
            }
        },
    )
}

private fun exportName(item: DocListItem): String {
    val base =
        documentDisplayTitle(item.doc.title, item.doc.fileName)
            .substringBeforeLast('.')
            .ifBlank { "document" }
    return base
}
