@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.DesktopBridge
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.documentDisplayTitle
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.ReviewMode
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.detail_document
import no_papers_needed.composeapp.generated.resources.detail_document_position
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable_hint
import no_papers_needed.composeapp.generated.resources.detail_mark_reviewed
import no_papers_needed.composeapp.generated.resources.detail_mark_unreviewed
import no_papers_needed.composeapp.generated.resources.detail_next_document
import no_papers_needed.composeapp.generated.resources.detail_previous_document
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen document view used on compact layouts (phones, narrow windows): the shared
 * [MetadataPanel] with the page viewer as its leading **Preview** tab, so the viewer and the
 * metadata live in one tabbed surface. Wide layouts never navigate here — the library's side
 * panel carries the same content there.
 */
@Composable
@Suppress("LongMethod")
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
    val ui by vm.ui.collectAsStateWithLifecycle()
    val metadata by vm.metadata.collectAsStateWithLifecycle()
    val actions = rememberMetadataActions(vm)

    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    // Focus the viewer on entry (and when switching docs) so its keyboard shortcuts work on desktop.
    // Re-run when the document finishes loading so the request happens after the viewer node is
    // attached; wait one frame so it is registered with the focus owner before asking.
    val viewerFocus = remember { FocusRequester() }
    LaunchedEffect(docId, metadata.document.item != null) {
        if (metadata.document.item != null) {
            withFrameNanos { }
            runCatching { viewerFocus.requestFocus() }
        }
    }

    // Doc-to-doc paging within the browse order the library handed us.
    val prevDocId = browse.neighbour(docId, -1)
    val nextDocId = browse.neighbour(docId, 1)
    val browsePosition = browse.positionOf(docId)
    val goPrevDoc: (() -> Unit)? = prevDocId?.let { { onNavigateToDoc(it) } }
    val goNextDoc: (() -> Unit)? = nextDocId?.let { { onNavigateToDoc(it) } }

    val current = metadata.document.item
    if (current == null) {
        UnavailableDocScaffold(onBack)
        return
    }

    val reviewed = metadata.document.reviewed
    // Manual reviewed toggle only makes sense in inbox-workflow mode (derived otherwise).
    val canToggleReviewed = metadata.document.reviewMode == ReviewMode.INBOX_WORKFLOW

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
                onBack = onBack,
                onToggleReviewed = { vm.setReviewed(!reviewed) },
                actions = {
                    DocActions(item = current, vm = vm, snackbar = snackbar, onGone = onBack)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        MetadataPanel(
            state = metadata,
            actions = actions,
            previewTab = { m ->
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
            },
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

@Composable
private fun UnavailableDocScaffold(onBack: () -> Unit) {
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
}

@Composable
private fun DetailTopBar(
    title: String,
    browsePosition: Pair<Int, Int>?,
    onPrevDoc: (() -> Unit)?,
    onNextDoc: (() -> Unit)?,
    reviewed: Boolean,
    canToggleReviewed: Boolean,
    onBack: () -> Unit,
    onToggleReviewed: () -> Unit,
    actions: @Composable () -> Unit,
) {
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
            actions()
        },
    )
}
