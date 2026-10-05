package dev.npn.app.ui.detail

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.DesktopBridge
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_document
import no_papers_needed.composeapp.generated.resources.detail_document_position
import no_papers_needed.composeapp.generated.resources.detail_next_document
import no_papers_needed.composeapp.generated.resources.detail_previous_document
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen document view used on compact layouts (phones, narrow windows): the shared
 * [DocumentView] under a top bar that pages through the library's browse order. Wide layouts never
 * navigate here — the library's side panel carries the same content there.
 */
@Composable
fun DocDetailScreen(
    docId: String,
    session: LibrarySession,
    settings: SettingsRepository,
    browse: BrowseContext,
    onBack: () -> Unit,
    onNavigateToDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbar = remember { SnackbarHostState() }
    // The viewer takes focus once the document is in, so its keyboard shortcuts work on desktop.
    val viewerFocus = remember { FocusRequester() }

    // Doc-to-doc paging within the browse order the library handed us.
    val browsePosition = browse.positionOf(docId)
    val goPrevDoc: (() -> Unit)? = browse.neighbour(docId, -1)?.let { { onNavigateToDoc(it) } }
    val goNextDoc: (() -> Unit)? = browse.neighbour(docId, 1)?.let { { onNavigateToDoc(it) } }

    DocumentView(
        docId = docId,
        session = session,
        settings = settings,
        snackbar = snackbar,
        onClose = onBack,
        onReviewNext = { goNextDoc?.invoke() ?: onBack() },
        hasNext = goNextDoc != null,
        modifier = modifier,
        viewerModifier = Modifier.focusRequester(viewerFocus).focusable(),
        searchQuery = browse.searchQuery,
    ) { loadState, actions, editor ->
        // Loading and missing are distinct: a null item before the first observeDoc emission is
        // still loading, and must not flash the "unavailable" surface. Only a confirmed absence does.
        when (loadState) {
            is DocLoadState.Loading -> {
                DocPlaceholderScaffold(onBack) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            is DocLoadState.Missing -> {
                DocPlaceholderScaffold(onBack) { padding -> DocumentUnavailable(Modifier.padding(padding)) }
            }

            is DocLoadState.Present -> {
                val title = loadState.item.doc.displayTitle
                // Reflect the open document in the native window title (desktop); inert elsewhere.
                LaunchedEffect(title) { DesktopBridge.windowTitle.value = title }
                // Wait one frame so the viewer is registered with the focus owner before asking.
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    runCatching { viewerFocus.requestFocus() }
                }
                LoadedDocScreen(
                    title = title,
                    snackbar = snackbar,
                    topBarActions = actions,
                    editor = editor,
                    browsePosition = browsePosition,
                    goPrevDoc = goPrevDoc,
                    goNextDoc = goNextDoc,
                    onBack = onBack,
                )
            }
        }
    }
}

/**
 * The scaffold for a resolved document: a top bar (with document navigation on wide widths), the
 * tabbed editor + page viewer, and — on compact widths — a separate [DocumentNavPill] so paging
 * survives the narrower bar without obscuring content.
 */
@Composable
private fun LoadedDocScreen(
    title: String,
    snackbar: SnackbarHostState,
    /** The document actions for the top bar, given whether the bar is compact. */
    topBarActions: @Composable (compact: Boolean) -> Unit,
    /** The tabbed editor with the page viewer, filling the space below the bar. */
    editor: @Composable (Modifier) -> Unit,
    browsePosition: Pair<Int, Int>?,
    goPrevDoc: (() -> Unit)?,
    goNextDoc: (() -> Unit)?,
    onBack: () -> Unit,
) {
    // The prev/next controls and the "3 of 41" position only show when there is somewhere to go.
    val position = browsePosition?.takeIf { it.second > 1 }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // On a narrow bar the title, position, prev/next and the share + overflow actions crowd
        // each other; below the breakpoint the prev/next controls move out of the top bar and into
        // a floating pill over the content, leaving the bar just the title and document actions.
        val compact = maxWidth < CompactTopBarBreakpoint
        Scaffold(
            modifier = Modifier.docPagingKeys(goPrevDoc, goNextDoc),
            topBar = {
                Column {
                    BackTopBar(
                        title = title,
                        onBack = onBack,
                        subtitle = position?.let { positionText(it) },
                    ) {
                        if (position != null && !compact) DocumentNav(goPrevDoc, goNextDoc)
                        topBarActions(compact)
                    }
                    if (position != null && compact) {
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
                editor(Modifier.fillMaxSize())
            }
        }
    }
}

/** Below this width the detail top bar drops prev/next to avoid crowding; see [DocumentNavPill]. */
private val CompactTopBarBreakpoint = 480.dp

/** "3 of 41": where the document sits in the browse order. */
@Composable
private fun positionText(browsePosition: Pair<Int, Int>): String =
    stringResource(Res.string.detail_document_position, browsePosition.first, browsePosition.second)

/**
 * Previous/next document within the browse order, each disabled at its end, with the "3 of 41"
 * [position] between them when given.
 */
@Composable
private fun DocumentNav(
    onPrevDoc: (() -> Unit)?,
    onNextDoc: (() -> Unit)?,
    position: Pair<Int, Int>? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TooltipIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
            label = stringResource(Res.string.detail_previous_document),
            onClick = { onPrevDoc?.invoke() },
            enabled = onPrevDoc != null,
        )
        position?.let { Text(positionText(it), style = MaterialTheme.typography.labelLarge) }
        TooltipIconButton(
            icon = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            label = stringResource(Res.string.detail_next_document),
            onClick = { onNextDoc?.invoke() },
            enabled = onNextDoc != null,
        )
    }
}

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
        DocumentNav(onPrevDoc, onNextDoc, browsePosition)
    }
}

/** The screen before the document loads or once it is gone: a back bar over [content]. */
@Composable
private fun DocPlaceholderScaffold(
    onBack: () -> Unit,
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        topBar = { BackTopBar(title = stringResource(Res.string.detail_document), onBack = onBack) },
        content = content,
    )
}
