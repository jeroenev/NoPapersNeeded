package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.documentDisplayTitle
import dev.npn.app.ui.detail.DocActions
import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.app.ui.detail.MetadataPanel
import dev.npn.app.ui.detail.PageViewer
import dev.npn.core.app.DocListItem
import dev.npn.core.app.LibrarySession
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_preview_close
import org.jetbrains.compose.resources.stringResource

/**
 * Side-panel document view on wide layouts, with everything the compact full-screen version has:
 * the full multi-page [PageViewer] (swipe/keys, zoom) as the leading **Preview** tab, the shared
 * [MetadataPanel] tabs (Details / Text / History, fully editable), and the document actions
 * (share/export, rename, re-run OCR, split, bake, trash) in the header. Wide layouts never
 * navigate to the full-screen detail route — this panel is the document view there.
 *
 * The panel hosts its own [DocDetailViewModel] in a store scoped to the previewed document, so
 * switching documents (or closing the panel) disposes the previous view model instead of piling
 * them up in the library screen's store.
 */
@Composable
internal fun DocPreviewPanel(
    item: DocListItem,
    session: LibrarySession,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, tonalElevation = 3.dp) {
        ScopedToDoc(docId = item.doc.docId) {
            PreviewBody(item = item, session = session, snackbar = snackbar, onClose = onClose)
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

@Composable
private fun PreviewBody(
    item: DocListItem,
    session: LibrarySession,
    snackbar: SnackbarHostState,
    onClose: () -> Unit,
) {
    val docId = item.doc.docId
    val vm: DocDetailViewModel = viewModel { DocDetailViewModel(docId, session) }
    SnackbarMessages(vm.messages, snackbar)

    val ui by vm.ui.collectAsStateWithLifecycle()
    val correspondents by vm.correspondents.collectAsStateWithLifecycle()
    val docTypes by vm.docTypes.collectAsStateWithLifecycle()
    val tagColors by vm.tagColors.collectAsStateWithLifecycle()
    val fieldKeys by vm.fieldKeys.collectAsStateWithLifecycle()
    val ocrText by vm.ocrText.collectAsStateWithLifecycle()
    val ocrVersions by vm.ocrVersions.collectAsStateWithLifecycle()
    val reviewMode by vm.reviewMode.collectAsStateWithLifecycle()
    val standardFields by vm.standardFields.collectAsStateWithLifecycle()
    val noteEditor by vm.noteEditor.collectAsStateWithLifecycle()
    val aiTags by vm.aiTags.collectAsStateWithLifecycle()
    val aiMetadata by vm.aiMetadata.collectAsStateWithLifecycle()
    val history by vm.history.collectAsStateWithLifecycle()
    val allTagNames = remember(tagColors) { tagColors.keys.toList() }

    Column(Modifier.fillMaxSize()) {
        PreviewHeader(
            item = item,
            actions = {
                // Trash/split remove the document from the result set; close the panel with them.
                DocActions(item = item, vm = vm, snackbar = snackbar, onGone = onClose)
            },
            onClose = onClose,
        )
        HorizontalDivider()
        MetadataPanel(
            item = item,
            allTagNames = allTagNames,
            tagColors = tagColors,
            correspondents = correspondents,
            docTypes = docTypes,
            fieldKeys = fieldKeys,
            ocrText = ocrText,
            ocrVersions = ocrVersions,
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
            reviewed = item.effectiveReviewed(reviewMode),
            onSetReviewed = vm::setReviewed,
            onAddTag = vm::addTag,
            onAddTagWithStyle = vm::addTagWithStyle,
            visibleFields = standardFields,
            onRemoveTag = vm::removeTag,
            aiTags = aiTags,
            onSuggestAiTags = vm::suggestAiTags,
            onAcceptAiTag = vm::acceptAiTag,
            onDismissAiTag = vm::dismissAiTag,
            onClearAiTagSuggestions = vm::clearAiTagSuggestions,
            aiMetadata = aiMetadata,
            onSuggestAiMetadata = vm::suggestAiMetadata,
            onAcceptAiMetadata = vm::acceptAiMetadata,
            onDismissAiMetadata = vm::dismissAiMetadata,
            history = history,
            onLoadHistory = { vm.loadHistory() },
            onRefreshHistory = { vm.loadHistory(refresh = true) },
            previewTab = { m ->
                // Keyed on the document: the viewer's remembered state (page-bitmap cache, pager
                // position, zoom) must not survive a document switch, or it would briefly show
                // the previous document's rendered pages.
                key(docId) {
                    PageViewer(
                        pageIndex = ui.pageIndex,
                        pageCount = ui.pageCount,
                        render = { p, maxDim -> vm.renderPage(p, maxDim) },
                        onPageChange = vm::goToPage,
                        modifier = m,
                    )
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

@Composable
private fun PreviewHeader(
    item: DocListItem,
    actions: @Composable () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            documentDisplayTitle(item.doc.title, item.doc.fileName),
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        actions()
        IconButton(onClick = onClose) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(Res.string.library_preview_close),
            )
        }
    }
}
