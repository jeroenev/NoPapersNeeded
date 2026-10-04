@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
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
import dev.npn.app.ui.common.DesktopBridge
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.detail_document
import no_papers_needed.composeapp.generated.resources.detail_document_position
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable_hint
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

    // Loading and missing are distinct: a null item before the first observeDoc emission is still
    // loading, and must not flash the "unavailable" surface. Only a confirmed absence does.
    val current =
        when (val loadState = metadata.document.loadState) {
            is DocLoadState.Loading -> {
                LoadingDocScaffold(onBack)
                return
            }

            is DocLoadState.Missing -> {
                UnavailableDocScaffold(onBack)
                return
            }

            is DocLoadState.Present -> {
                loadState.item
            }
        }

    // Reflect the open document in the native window title (desktop); inert elsewhere.
    LaunchedEffect(current.doc.displayTitle) { DesktopBridge.windowTitle.value = current.doc.displayTitle }

    LoadedDocScreen(
        current = current,
        ui = ui,
        metadata = metadata,
        actions = actions,
        vm = vm,
        snackbar = snackbar,
        viewerFocus = viewerFocus,
        browsePosition = browsePosition,
        goPrevDoc = goPrevDoc,
        goNextDoc = goNextDoc,
        onBack = onBack,
    )
}

/**
 * The scaffold for a resolved document: a top bar (with document navigation on wide widths), the
 * tabbed editor + page viewer, and — on compact widths — a separate [DocumentNavPill] so paging
 * survives the narrower bar without obscuring content.
 */
@Composable
private fun LoadedDocScreen(
    current: DocListItem,
    ui: DetailUiState,
    metadata: MetadataUiState,
    actions: MetadataActions,
    vm: DocDetailViewModel,
    snackbar: SnackbarHostState,
    viewerFocus: FocusRequester,
    browsePosition: Pair<Int, Int>?,
    goPrevDoc: (() -> Unit)?,
    goNextDoc: (() -> Unit)?,
    onBack: () -> Unit,
) {
    // Whether there is more than one document to page through; the prev/next controls only show
    // when there is somewhere to go.
    val canBrowse = browsePosition != null && browsePosition.second > 1
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // On a narrow bar the title, position, prev/next and the share + overflow actions crowd
        // each other; below the breakpoint the prev/next controls move out of the top bar and into
        // a floating pill over the content, leaving the bar just the title and document actions.
        val compact = maxWidth < CompactTopBarBreakpoint
        val showNavInTopBar = canBrowse && !compact
        Scaffold(
            modifier = Modifier.docPagingKeys(goPrevDoc, goNextDoc),
            topBar = {
                Column {
                    DetailTopBar(
                        title = current.doc.displayTitle,
                        browsePosition = browsePosition,
                        onPrevDoc = goPrevDoc.takeIf { showNavInTopBar },
                        onNextDoc = goNextDoc.takeIf { showNavInTopBar },
                        showNav = showNavInTopBar,
                        onBack = onBack,
                        actions = {
                            DocActions(
                                item = current,
                                vm = vm,
                                onGone = onBack,
                                compact = compact,
                            )
                        },
                    )
                    browsePosition?.takeIf { canBrowse && compact }?.let { position ->
                        DocumentNavPill(
                            browsePosition = position,
                            onPrevDoc = goPrevDoc,
                            onNextDoc = goNextDoc,
                            modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp),
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                DocumentEditor(
                    state = metadata,
                    actions = actions,
                    onReviewed = { goNextDoc?.invoke() ?: onBack() },
                    hasNext = goNextDoc != null,
                    preview = { m ->
                        PageViewer(
                            pageIndex = ui.pageIndex,
                            pageCount = ui.pageCount,
                            render = { p, maxDim -> vm.renderPage(p, maxDim) },
                            onPageChange = vm::goToPage,
                            onRetryPageCount = vm::retryPageCount,
                            modifier =
                                m
                                    .focusRequester(viewerFocus)
                                    .focusable(),
                        )
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/** Ctrl+Left/Right walks to the prev/next document (desktop); a no-op when there is nowhere to go. */
private fun Modifier.docPagingKeys(
    goPrevDoc: (() -> Unit)?,
    goNextDoc: (() -> Unit)?,
): Modifier =
    onKeyEvent { event ->
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
    }

/** Below this width the detail top bar drops prev/next to avoid crowding; see [DocumentNavPill]. */
private val CompactTopBarBreakpoint = 480.dp

/**
 * Prev/next document control below the compact top bar, carrying the same "3 of 41" position as the
 * wide top bar. Keeping it in the scaffold's top area avoids obscuring the panel tabs or fields.
 */
@Composable
private fun DocumentNavPill(
    browsePosition: Pair<Int, Int>,
    onPrevDoc: (() -> Unit)?,
    onNextDoc: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        tonalElevation = 3.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TooltipIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                label = stringResource(Res.string.detail_previous_document),
                onClick = { onPrevDoc?.invoke() },
                enabled = onPrevDoc != null,
            )
            Text(
                stringResource(
                    Res.string.detail_document_position,
                    browsePosition.first,
                    browsePosition.second,
                ),
                style = MaterialTheme.typography.labelLarge,
            )
            TooltipIconButton(
                icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                label = stringResource(Res.string.detail_next_document),
                onClick = { onNextDoc?.invoke() },
                enabled = onNextDoc != null,
            )
        }
    }
}

@Composable
private fun LoadingDocScaffold(onBack: () -> Unit) {
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
        Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
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
    showNav: Boolean,
    onBack: () -> Unit,
    actions: @Composable () -> Unit,
) {
    TopAppBar(
        title = {
            Column {
                Text(title, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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
            // Prev/next document within the browse order; on compact widths these move to the
            // floating DocumentNavPill instead, so the narrow bar is not crowded.
            if (showNav) {
                TooltipIconButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                    label = stringResource(Res.string.detail_previous_document),
                    onClick = { onPrevDoc?.invoke() },
                    enabled = onPrevDoc != null,
                )
                TooltipIconButton(
                    icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    label = stringResource(Res.string.detail_next_document),
                    onClick = { onNextDoc?.invoke() },
                    enabled = onNextDoc != null,
                )
            }
            actions()
        },
    )
}
