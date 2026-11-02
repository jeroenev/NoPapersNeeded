package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.settings.SettingsRepository

/**
 * One document as both the detail screen and the library's side panel show it, wired to its
 * [DocDetailViewModel]: the viewmodel's messages go to [snackbar], and [frame] lays out the rest
 * with the host's own chrome. It gets the document's [DocLoadState], the document actions for a top
 * bar or header (given whether the bar is compact), and the editor: the page viewer, with
 * [viewerModifier], and the metadata tabs under one save model.
 *
 * [onClose] runs once the document left the library from here (trashed, or split into new
 * documents), as the viewmodel's [DetailUiState.left] reports.
 * [onReviewNext] and [hasNext] drive the save bar's "mark reviewed" button.
 *
 * The viewmodel is the one in [viewModels], the document's own store, which every host showing the
 * document shares: expanding the side panel into the detail screen keeps its page, its loaded text
 * and history, and AI suggestions already paid for. Built on first use, seeded with [searchQuery]'s
 * terms and, when the caller already has the row, [initialItem]; the seeds are read once, so a host
 * showing it again with other seeds (a recreated window whose browse order is gone) finds the same
 * viewmodel rather than a second one.
 *
 * The page is the one thing here a restored process would visibly lose, and no back-stack entry
 * saves the document's store, so this view saves the page shown, and a viewmodel it builds starts
 * there. One the store still holds keeps its own page.
 */
@Suppress("ViewModelForwarding") // vm reaches the editor and the actions only as DocumentWrites and DocumentActions
@Composable
internal fun DocumentView(
    docId: String,
    session: LibrarySession,
    settings: SettingsRepository,
    /** The document's own viewmodel store, shared by the hosts showing it. */
    viewModels: ViewModelStoreOwner,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onReviewNext: () -> Unit,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
    viewerModifier: Modifier = Modifier,
    searchQuery: String? = null,
    initialItem: DocListItem? = null,
    frame: @Composable (
        loadState: DocLoadState,
        actions: @Composable (compact: Boolean) -> Unit,
        editor: @Composable (Modifier) -> Unit,
    ) -> Unit,
) {
    var shownPage by rememberSaveable(docId) { mutableIntStateOf(0) }
    // What outlives this view (a trash and its Undo) is shown by the library shell, not [snackbar].
    val notices = LocalDocumentDrafts.current.notices

    @Suppress("ViewModelInjection") // not a parameter's default: a viewmodel built here starts at shownPage
    val vm =
        viewModel(viewModelStoreOwner = viewModels) {
            DocDetailViewModel(
                docId,
                session,
                settings,
                searchQuery,
                initialItem,
                initialPage = shownPage,
                notices = notices,
            )
        }
    SnackbarMessages(vm.messages, snackbar)
    val ui by vm.ui.collectAsStateWithLifecycle()
    DisposableEffect(ui.pageIndex) {
        shownPage = ui.pageIndex
        onDispose {}
    }
    val metadata by vm.metadata.collectAsStateWithLifecycle()
    val currentOnClose by rememberUpdatedState(onClose)
    DisposableEffect(ui.left) {
        if (ui.left) {
            vm.closed()
            currentOnClose()
        }
        onDispose {}
    }

    Box(modifier) {
        frame(
            metadata.document.loadState,
            { compact ->
                metadata.document.item?.let { item ->
                    DocActions(item = item, state = ui, actions = vm, compact = compact)
                }
            },
            { editorModifier ->
                DocumentEditor(
                    state = metadata,
                    writes = vm,
                    onReviewNext = onReviewNext,
                    hasNext = hasNext,
                    preview = { previewModifier ->
                        // Keyed on the document: the viewer's remembered state (page-bitmap cache,
                        // pager position, zoom) must not survive a document switch, or it would
                        // briefly show the previous document's rendered pages.
                        key(docId) {
                            PageViewer(
                                initialPage = ui.pageIndex,
                                pageCount = ui.pageCount,
                                render = vm::renderPage,
                                onPageChange = vm::goToPage,
                                onRetryPageCount = vm::retryPageCount,
                                modifier = previewModifier.then(viewerModifier),
                            )
                        }
                    },
                    modifier = editorModifier,
                )
            },
        )
    }
}
