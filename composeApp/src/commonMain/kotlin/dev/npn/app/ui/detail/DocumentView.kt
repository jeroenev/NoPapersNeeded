package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.settings.SettingsRepository

/**
 * One document as both the detail screen and the library's side panel show it, wired to its [vm]:
 * the viewmodel's messages go to [snackbar], and [frame] lays out the rest with the host's own
 * chrome. It gets the document's [DocLoadState], the document actions for a top bar or header
 * (given whether the bar is compact), and the editor: the page viewer, with [viewerModifier], and
 * the metadata tabs under one save model.
 *
 * [onClose] runs once the document left the library from here (trashed, or split into new
 * documents), as the viewmodel's [DetailUiState.left] reports.
 * [onReviewNext] and [hasNext] drive the save bar's "mark reviewed" button.
 *
 * The [vm] defaults to the one in [viewModels], the document's own store, which every host showing
 * the document shares: expanding the side panel into the detail screen keeps its page, its loaded
 * text and history, and AI suggestions already paid for. Built on first use, seeded with
 * [searchQuery]'s terms and, when the caller already has the row, [initialItem]; the seeds are read
 * once, so a host showing it again with other seeds (a recreated window whose browse order is gone)
 * finds the same viewmodel rather than a second one.
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
    savedState: DocumentSavedState = rememberDocumentSavedState(docId),
    vm: DocDetailViewModel =
        viewModel(viewModelStoreOwner = viewModels) {
            DocDetailViewModel(docId, session, settings, searchQuery, initialItem, savedState.restored)
        },
    frame: @Composable (
        loadState: DocLoadState,
        actions: @Composable (compact: Boolean) -> Unit,
        editor: @Composable (Modifier) -> Unit,
    ) -> Unit,
) {
    // After composition: saving reads it, and nothing during composition does.
    SideEffect { savedState.of(vm.savedState) }
    SnackbarMessages(vm.messages, snackbar)
    val ui by vm.ui.collectAsStateWithLifecycle()
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

/**
 * Saves a document viewmodel's [SavedStateHandle] for a restored process. Its viewmodel lives in the
 * document's own store, which no back-stack entry saves, so each view showing it does: [restored] is
 * what the last process saved (empty for a document opened afresh), which a new viewmodel starts
 * from, and the view then saves the handle of the viewmodel it shows ([of]), whichever view built it.
 */
internal class DocumentSavedState(
    val restored: SavedStateHandle,
) {
    private var shown: SavedStateHandle? = null

    /** Save [handle], the shown viewmodel's, from now on. */
    fun of(handle: SavedStateHandle) {
        shown = handle
    }

    /** The handle's values as a flat key, value, key, value list; a viewmodel saves only strings. */
    fun saved(): List<String> {
        val handle = shown ?: restored
        return handle.keys().flatMap { key -> handle.get<String>(key)?.let { listOf(key, it) }.orEmpty() }
    }

    companion object {
        val Saver: Saver<DocumentSavedState, Any> =
            listSaver(
                save = { it.saved() },
                restore = { values ->
                    DocumentSavedState(SavedStateHandle(values.chunked(2).associate { (key, value) -> key to value }))
                },
            )
    }
}

/** The [DocumentSavedState] of [docId]'s viewmodel in this view. */
@Composable
internal fun rememberDocumentSavedState(docId: String): DocumentSavedState =
    rememberSaveable(docId, saver = DocumentSavedState.Saver) { DocumentSavedState(SavedStateHandle()) }
