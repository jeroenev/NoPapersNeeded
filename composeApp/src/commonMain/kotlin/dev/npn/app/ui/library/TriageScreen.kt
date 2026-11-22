package dev.npn.app.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.LoadingIndicator
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.PositionStepper
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.SectionHeading
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TagEditor
import dev.npn.app.ui.detail.DocDetailViewModel
import dev.npn.app.ui.detail.DocLoadState
import dev.npn.app.ui.detail.DocumentUnavailable
import dev.npn.app.ui.detail.DraftEditing
import dev.npn.app.ui.detail.EditorStatusBar
import dev.npn.app.ui.detail.FieldRow
import dev.npn.app.ui.detail.LocalDocumentDrafts
import dev.npn.app.ui.detail.MetadataUiState
import dev.npn.app.ui.detail.PageViewer
import dev.npn.app.ui.detail.rememberDraftEditing
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.reviewMarkerIsAuthoritative
import dev.npn.core.settings.SettingsRepository
import dev.npn.core.util.runCatchingCancellable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_tags
import no_papers_needed.composeapp.generated.resources.triage_next_document
import no_papers_needed.composeapp.generated.resources.triage_previous_document
import no_papers_needed.composeapp.generated.resources.triage_progress
import no_papers_needed.composeapp.generated.resources.triage_title
import org.jetbrains.compose.resources.stringResource

/** The registry keys the triage mode edits, in the order they appear. */
private val EssentialFields = listOf(FieldKeys.TITLE, FieldKeys.CORRESPONDENT, FieldKeys.DOC_TYPE, FieldKeys.DOC_DATE)

/**
 * The Inbox in triage mode: one document at a time, with its page preview and the fields that clear
 * the inbox fastest (title, correspondent, type, date and tags), plus the shared save/review status
 * bar. Next/previous skip without marking; "Mark reviewed & next" saves the draft and the marker
 * through the same [dev.npn.app.ui.detail.DocumentDrafts] path the detail editor uses, then moves
 * on. The last document's action finishes and leaves the mode.
 *
 * The mode starts over the result set the library already loaded, so it adds no query and no whole
 * library load; its order and unreviewed count are fixed at entry, while the document on screen
 * follows its own viewmodel.
 */
@Composable
internal fun TriageScreen(
    state: TriageState,
    session: LibrarySession,
    settings: SettingsRepository,
    viewModels: ViewModelStoreOwner,
    snackbar: SnackbarHostState,
    actions: TriageActions,
    modifier: Modifier = Modifier,
) {
    val item = state.current
    if (item == null) {
        // The movers only pick ids the mode knows, so this is only reachable from a hand-built
        // state; leave rather than stranding the user on a surface with no way out.
        SideEffect { actions.exitTriage() }
        return
    }
    val drafts = LocalDocumentDrafts.current

    @Suppress("ViewModelInjection") // built here so it starts from the row the library already has
    val vm =
        viewModel(viewModelStoreOwner = viewModels) {
            DocDetailViewModel(item.doc.docId, session, settings, initialItem = item, notices = drafts.notices)
        }
    SnackbarMessages(vm.messages, snackbar)
    val metadata by vm.metadata.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val modeFocus = remember { FocusRequester() }
    // Every move lands the keyboard back on the mode, so J/K work at once after a button or a save.
    LaunchedEffect(state.currentId) {
        withFrameNanos { }
        runCatchingCancellable { modeFocus.requestFocus() }
    }
    val progress =
        stringResource(
            Res.string.triage_progress,
            state.position,
            state.total,
            state.unreviewedRemaining,
        )
    Scaffold(
        modifier =
            modifier
                .fillMaxSize()
                .focusRequester(modeFocus)
                .focusable()
                .triageKeys(
                    onNext = actions::triageNext,
                    onPrevious = actions::triagePrevious,
                    onExit = actions::exitTriage,
                ),
        topBar = {
            BackTopBar(
                title = stringResource(Res.string.triage_title),
                onBack = actions::exitTriage,
                subtitle = progress,
                // Next/previous swap the whole document under the user; say the new progress.
                subtitleModifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            ) {
                PositionStepper(
                    position = null,
                    previousLabel = stringResource(Res.string.triage_previous_document),
                    nextLabel = stringResource(Res.string.triage_next_document),
                    onPrevious = actions::triagePrevious.takeIf { state.hasPrevious },
                    onNext = actions::triageNext.takeIf { state.hasNext },
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            val document = metadata.document.item
            if (document == null) {
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    if (metadata.document.loadState is DocLoadState.Loading) {
                        LoadingIndicator()
                    } else {
                        DocumentUnavailable(Modifier.fillMaxSize())
                    }
                }
                return@Column
            }
            val editing = rememberDraftEditing(document.doc.docId, document, metadata.document.fieldDefs, vm)
            val preview: @Composable (Modifier) -> Unit = { previewModifier ->
                // Keyed on the document: the viewer's pager, zoom and page-bitmap cache must not
                // survive a move and show the previous document's rendered pages.
                key(document.doc.docId) {
                    PageViewer(
                        initialPage = ui.pageIndex,
                        pageCount = ui.pageCount,
                        render = vm::renderPage,
                        onPageChange = vm::goToPage,
                        onRetryPageCount = vm::retryPageCount,
                        modifier = previewModifier,
                    )
                }
            }
            TriageDocument(
                editing = editing,
                metadata = metadata,
                wide = LocalWindowWidthClass.current.isWide,
                preview = preview,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            val docId = document.doc.docId
            EditorStatusBar(
                draft = editing.draft,
                onRetry = editing.actions::commitDraft,
                onUndo = editing::undo,
                onDiscard = editing::discard,
                onReview =
                    if (metadata.document.reviewMode.reviewMarkerIsAuthoritative) {
                        {
                            if (metadata.document.reviewed) {
                                // Already reviewed: a redundant marker write is a journal event for
                                // no change, so this is a skip (or the finish at the last one).
                                if (state.hasNext) actions.triageNext() else actions.exitTriage()
                            } else {
                                editing.markReviewed { actions.triageReviewedAndAdvance(docId) }
                            }
                        }
                    } else {
                        null
                    },
                hasNext = state.hasNext,
            )
        }
    }
}

@Composable
private fun TriageDocument(
    editing: DraftEditing,
    metadata: MetadataUiState,
    wide: Boolean,
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    // One composition of the page viewer, moved between the wide and compact layouts rather than
    // built again in each: the viewer's pager, zoom and bitmap cache survive a window-width change.
    val latestPreview by rememberUpdatedState(preview)
    val previewContent = remember { movableContentOf<Modifier> { latestPreview(it) } }
    if (wide) {
        Row(modifier) {
            Box(Modifier.weight(1f).fillMaxHeight()) { previewContent(Modifier.fillMaxSize()) }
            Box(Modifier.width(1.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant))
            Box(Modifier.weight(1f).fillMaxHeight()) { TriageMetadata(editing, metadata, Modifier.fillMaxSize()) }
        }
    } else {
        Column(modifier) {
            Box(Modifier.fillMaxWidth().weight(0.45f)) { previewContent(Modifier.fillMaxSize()) }
            HorizontalDivider()
            TriageMetadata(editing, metadata, Modifier.fillMaxWidth().weight(0.55f))
        }
    }
}

/** The essential fields and the tags, edited through the document's draft exactly as the detail panel. */
@Composable
private fun TriageMetadata(
    editing: DraftEditing,
    metadata: MetadataUiState,
    modifier: Modifier = Modifier,
) {
    val defs = metadata.document.fieldDefs
    val doc = editing.item.doc
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReviewedBadge(metadata.document.reviewed)
        for (key in EssentialFields) {
            val def = defs.firstOrNull { it.key == key } ?: continue
            key(def.key) {
                FieldRow(
                    def = def,
                    value = doc.fields[def.key],
                    refused = def.key in editing.draft.refused,
                    suggestions = metadata.vocabulary.fieldValues[def.key].orEmpty(),
                    placeholder = doc.fileName.takeIf { def.key == FieldKeys.TITLE },
                    onCommit = { editing.actions.commitField(def.key, it) },
                    onDone = editing.actions::commitDraft,
                ) {}
            }
        }
        HorizontalDivider()
        SectionHeading(stringResource(Res.string.detail_tags), style = MaterialTheme.typography.labelLarge)
        TagEditor(
            tags = editing.item.tags,
            allTagNames = metadata.vocabulary.allTagNames,
            tagColors = metadata.vocabulary.tagColors,
            onAdd = editing.actions::addTag,
            onRemove = editing.actions::removeTag,
            onAddWithStyle = editing.actions::addTagWithStyle,
        )
    }
}
