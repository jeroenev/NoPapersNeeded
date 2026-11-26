package dev.npn.app.ui.detail

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.FloatingControls
import dev.npn.app.ui.common.FocusOnEntry
import dev.npn.app.ui.common.KeyedViewModelStores
import dev.npn.app.ui.common.LoadingIndicator
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.PositionStepper
import dev.npn.app.ui.common.PublishWindowTitle
import dev.npn.app.ui.common.ViewModelStoreLease
import dev.npn.core.app.LibrarySession
import dev.npn.core.settings.SettingsRepository
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_document
import no_papers_needed.composeapp.generated.resources.detail_document_position
import no_papers_needed.composeapp.generated.resources.detail_next_document
import no_papers_needed.composeapp.generated.resources.detail_previous_document
import org.jetbrains.compose.resources.stringResource

/**
 * Full-screen document view: the shared [DocumentView] under a top bar that pages through the
 * library's browse order. Compact layouts (phones, narrow windows) open documents here as a
 * horizontal pager over the library's current filtered and sorted list, with the metadata in a
 * draggable peek sheet over the preview; wide ones show a single document in the split pane and
 * come here only through the side panel's expand button, onto the same viewmodel, the one in the
 * document's own store [documentStores].
 *
 * The document shown is kept here rather than only in the route, so swiping to another document and
 * then crossing the wide/compact breakpoint keeps that document, and Back still returns to the
 * library as it was.
 */
@Composable
fun DocDetailScreen(
    docId: String,
    session: LibrarySession,
    settings: SettingsRepository,
    documentStores: KeyedViewModelStores<String>,
    browse: BrowseContext,
    onBack: () -> Unit,
    onNavigateToDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
    documents: ViewModelStoreLease<String> = viewModel { ViewModelStoreLease(documentStores) },
) {
    // Held for as long as this entry is on the back stack; shared with the library's side panel
    // when it shows the same document, so expanding the panel continues where it was.
    // The pager's selection, not just the route's: a swiped-to document stays current across a
    // width change, and a restored process can reopen it even when its browse order is gone.
    var shownDocId by rememberSaveable(docId) { mutableStateOf(docId) }
    // The order starts at what is on screen when that is still in the library's list, at the
    // route's document when the list holds it, and at what was on screen alone after a restore.
    val startDocId =
        when {
            shownDocId in browse.order -> shownDocId
            docId in browse.order -> docId
            else -> shownDocId
        }
    val order = remember(browse.order, startDocId) { DetailBrowseOrder.of(browse.order, startDocId) }
    val wide = LocalWindowWidthClass.current.isWide
    if (wide) {
        val currentDocId = shownDocId.takeIf { it in order.ids } ?: docId
        // The route's lease shares the document's store with the library's side panel, but must not
        // keep every document it ever showed: the panel's own holder keeps its preview alive.
        SideEffect { documents.keepOnly(currentDocId) }
        DocPage(
            state = DocPageState(currentDocId, documents.of(currentDocId), browse),
            actions =
                DocPageActions(
                    onBack = onBack,
                    onReviewNext = { browse.neighbour(currentDocId, 1)?.let(onNavigateToDoc) ?: onBack() },
                    goPrevDoc = browse.neighbour(currentDocId, -1)?.let { { onNavigateToDoc(it) } },
                    goNextDoc = browse.neighbour(currentDocId, 1)?.let { { onNavigateToDoc(it) } },
                ),
            session = session,
            settings = settings,
            modifier = modifier,
        )
        return
    }
    DocumentPager(
        order = order,
        modifier = modifier,
        onShowDocument = {
            shownDocId = it
            documents.of(it)
            documents.keepOnly(it)
        },
    ) { page ->
        val pageDocId = page.docId
        val pageStore = rememberPageStore(documentStores, pageDocId)
        val peekSheet = rememberSaveable(pageDocId, saver = PeekSheetState.Saver) { PeekSheetState() }
        HoldSwipeWhileEditing(page, peekSheet)
        DocPage(
            state =
                DocPageState(
                    docId = pageDocId,
                    viewModels = pageStore,
                    browse = browse,
                    peekSheet = peekSheet,
                    // The document pager owns horizontal swipes; the preview's page controls still turn pages.
                    swipePreviewPages = order.ids.size <= 1,
                    publishWindowTitle = page.isCurrent,
                ),
            actions =
                DocPageActions(
                    onBack = onBack,
                    onReviewNext = { page.whenStillSelected { page.onNext?.invoke() ?: onBack() } },
                    goPrevDoc = page.onPrevious,
                    goNextDoc = page.onNext,
                ),
            session = session,
            settings = settings,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** A composed pager page keeps its own hold until it leaves, independently of the route. */
@Composable
internal fun rememberPageStore(
    stores: KeyedViewModelStores<String>,
    docId: String,
): ViewModelStoreOwner {
    val holder = remember(docId, stores) { Any() }
    val owner = remember(docId, stores) { stores.hold(docId, holder) }
    DisposableEffect(docId, stores) {
        onDispose { stores.release(docId, holder) }
    }
    return owner
}

private data class DocPageState(
    val docId: String,
    val viewModels: ViewModelStoreOwner,
    val browse: BrowseContext,
    val peekSheet: PeekSheetState? = null,
    val swipePreviewPages: Boolean = true,
    val publishWindowTitle: Boolean = true,
)

private data class DocPageActions(
    val onBack: () -> Unit,
    val onReviewNext: () -> Unit,
    val goPrevDoc: (() -> Unit)?,
    val goNextDoc: (() -> Unit)?,
)

/** One document in the detail route, with a peek sheet on compact widths. */
@Composable
private fun DocPage(
    state: DocPageState,
    actions: DocPageActions,
    session: LibrarySession,
    settings: SettingsRepository,
    modifier: Modifier = Modifier,
) {
    val docId = state.docId
    val viewModels = state.viewModels
    val browse = state.browse
    val peekSheet = state.peekSheet
    val swipePreviewPages = state.swipePreviewPages
    val publishWindowTitle = state.publishWindowTitle
    val onBack = actions.onBack
    val onReviewNext = actions.onReviewNext
    val goPrevDoc = actions.goPrevDoc
    val goNextDoc = actions.goNextDoc
    val snackbar = remember(docId) { SnackbarHostState() }
    // The viewer takes focus once the document is in, so its keyboard shortcuts work on desktop.
    val viewerFocus = remember(docId) { FocusRequester() }
    val browsePosition = browse.positionOf(docId)
    // The top bar's Back collapses an expanded sheet first; only a collapsed one leaves the detail.
    // A document that left the library is not the sheet's business and closes at once.
    val leave = { if (peekSheet?.collapseIfExpanded() != true) onBack() }

    DocumentView(
        docId = docId,
        session = session,
        settings = settings,
        viewModels = viewModels,
        snackbar = snackbar,
        onClose = onBack,
        onReviewNext = onReviewNext,
        hasNext = goNextDoc != null,
        modifier = modifier,
        viewerModifier = Modifier.focusRequester(viewerFocus).focusable(),
        searchQuery = browse.searchQuery,
        peekSheet = peekSheet,
        swipePreviewPages = swipePreviewPages,
    ) { loadState, topBarActions, editor ->
        // Loading and missing are distinct: a null item before the first observeDoc emission is
        // still loading, and must not flash the "unavailable" surface. Only a confirmed absence does.
        when (loadState) {
            is DocLoadState.Loading -> {
                DocPlaceholderScaffold(leave) { padding -> LoadingIndicator(Modifier.padding(padding)) }
            }

            is DocLoadState.Missing -> {
                val drafts = LocalDocumentDrafts.current
                val draft = drafts.state[docId]
                // The resume banner and the library switch bring the user here for a draft whose
                // document went; this is the one place it can still be dropped.
                val discard = { drafts.discard(docId) }.takeIf { draft != null && draft.dirty && !draft.saving }
                DocPlaceholderScaffold(leave) { padding ->
                    DocumentUnavailable(Modifier.padding(padding), onDiscardDraft = discard)
                }
            }

            is DocLoadState.Present -> {
                val title = loadState.item.doc.displayTitle
                // Reflect the open document in the native window title (desktop); inert elsewhere.
                // In the pager only the settled page names the window, never a neighbour sliding in.
                if (publishWindowTitle) PublishWindowTitle(title)
                FocusOnEntry(viewerFocus)
                LoadedDocScreen(
                    title = title,
                    snackbar = snackbar,
                    topBarActions = topBarActions,
                    editor = editor,
                    browsePosition = browsePosition,
                    goPrevDoc = goPrevDoc,
                    goNextDoc = goNextDoc,
                    onBack = leave,
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
        // Measured here rather than read from LocalWindowWidthClass: it is about what fits this bar,
        // well below the width at which the window's layout changes.
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
    PositionStepper(
        position = position?.let { positionText(it) },
        previousLabel = stringResource(Res.string.detail_previous_document),
        nextLabel = stringResource(Res.string.detail_next_document),
        onPrevious = onPrevDoc,
        onNext = onNextDoc,
    )
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
    FloatingControls(modifier) {
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
