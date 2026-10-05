package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.SettingsRepository

/**
 * One document as both the detail screen and the library's side panel show it, wired to its [vm]:
 * the viewmodel's messages go to [snackbar], and [frame] lays out the rest with the host's own
 * chrome. It gets the document's [DocLoadState], the document actions for a top bar or header
 * (given whether the bar is compact), and the editor: the page viewer, with [viewerModifier], and
 * the metadata tabs under one save model.
 *
 * [onClose] runs once the document left the library (trashed, or split into new documents).
 * [onReviewNext] and [hasNext] drive the save bar's "mark reviewed" button.
 *
 * The [vm] defaults to one for [docId] in the current [androidx.lifecycle.ViewModelStoreOwner],
 * seeded with [searchQuery]'s terms and, when the caller already has the row, [initialItem].
 */
@Suppress("ViewModelForwarding") // vm reaches the editor and the actions only as DocumentWrites and DocumentActions
@Composable
internal fun DocumentView(
    docId: String,
    session: LibrarySession,
    settings: SettingsRepository,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onReviewNext: () -> Unit,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
    viewerModifier: Modifier = Modifier,
    searchQuery: String? = null,
    initialItem: DocListItem? = null,
    vm: DocDetailViewModel =
        viewModel(key = "detail-$docId-${searchQuery.orEmpty()}") {
            DocDetailViewModel(docId, session, settings, searchQuery, initialItem)
        },
    frame: @Composable (
        loadState: DocLoadState,
        actions: @Composable (compact: Boolean) -> Unit,
        editor: @Composable (Modifier) -> Unit,
    ) -> Unit,
) {
    SnackbarMessages(vm.messages, snackbar)
    val ui by vm.ui.collectAsStateWithLifecycle()
    val metadata by vm.metadata.collectAsStateWithLifecycle()

    Box(modifier) {
        frame(
            metadata.document.loadState,
            { compact ->
                metadata.document.item?.let { item ->
                    DocActions(item = item, state = ui, actions = vm, onClose = onClose, compact = compact)
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
                                pageIndex = ui.pageIndex,
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
