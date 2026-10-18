package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.PanelHeader
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.WindowWidthClass
import dev.npn.app.ui.detail.DocumentView
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import dev.npn.core.settings.SettingsRepository
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_preview_close
import no_papers_needed.composeapp.generated.resources.ux_expand_document
import org.jetbrains.compose.resources.stringResource

/**
 * Side-panel document view on wide layouts, with everything the compact full-screen version has:
 * the shared [DocumentView] (the page viewer as the leading **Preview** tab, the metadata tabs,
 * fully editable) under a header with the document actions (share/export, rename, re-run OCR,
 * split, bake, trash). Opening a document on a wide layout shows it here; the header's expand
 * button ([onExpand]) opens the full-screen detail route.
 *
 * The panel hosts the document's viewmodel in a store scoped to the previewed document, so
 * switching documents (or closing the panel) disposes the previous view model instead of piling
 * them up in the library screen's store.
 */
@Composable
internal fun DocPreviewPanel(
    item: DocListItem,
    session: LibrarySession,
    settings: SettingsRepository,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    onExpand: () -> Unit,
    onReviewNext: () -> Unit,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, tonalElevation = 3.dp) {
        // A column beside the list, never wider than the wide breakpoint (see SidePanelMaxWidth):
        // the document view inside lays out as it would on a compact window, one tab at a time.
        CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.COMPACT) {
            ScopedToDoc(docId = item.doc.docId) {
                DocumentView(
                    docId = item.doc.docId,
                    session = session,
                    settings = settings,
                    snackbar = snackbar,
                    // Trash and split take the document out of the result set; close the panel.
                    onClose = onClose,
                    onReviewNext = onReviewNext,
                    hasNext = hasNext,
                    // Seeded with the row the library list already loaded: the viewmodel is rebuilt
                    // for every document the user selects (see ScopedToDoc), so without the seed the
                    // panel would blank for one index query on every switch.
                    initialItem = item,
                ) { _, actions, editor ->
                    // This panel is a sibling of the library's Scaffold, so nothing applies the
                    // bottom system-bar inset for it: without this the pinned save/discard/review
                    // row would draw under the Android navigation bar on wide edge-to-edge layouts.
                    // The inset is zero on desktop.
                    Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                        PanelHeader(
                            title = item.doc.displayTitle,
                            closeLabel = stringResource(Res.string.library_preview_close),
                            onClose = onClose,
                        ) {
                            TooltipIconButton(
                                Icons.Filled.OpenInFull,
                                stringResource(Res.string.ux_expand_document),
                                onExpand,
                            )
                            actions(true)
                        }
                        HorizontalDivider()
                        editor(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
}

/**
 * Runs [content] under a [ViewModelStoreOwner] tied to [docId]: a new store per previewed
 * document, cleared when the document changes or the panel leaves composition.
 */
@Composable
private fun ScopedToDoc(
    docId: String,
    content: @Composable () -> Unit,
) {
    val owner =
        remember(docId) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
    DisposableEffect(owner) { onDispose { owner.viewModelStore.clear() } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides owner) { content() }
}
