package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.TechnicalDetails
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.reviewMarkerIsAuthoritative
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_retry
import no_papers_needed.composeapp.generated.resources.detail_draft_invalid
import no_papers_needed.composeapp.generated.resources.detail_saved
import no_papers_needed.composeapp.generated.resources.detail_saving
import no_papers_needed.composeapp.generated.resources.library_undo
import no_papers_needed.composeapp.generated.resources.ux_discard
import no_papers_needed.composeapp.generated.resources.ux_discard_prompt
import no_papers_needed.composeapp.generated.resources.ux_discard_short
import no_papers_needed.composeapp.generated.resources.ux_review_finish
import no_papers_needed.composeapp.generated.resources.ux_review_next
import no_papers_needed.composeapp.generated.resources.ux_save_failed_summary
import org.jetbrains.compose.resources.stringResource

/**
 * One save model for all editable document fields, in either responsive presentation. Field values
 * go into the document's draft as they are typed and are written by the draft store
 * ([DocumentDrafts.commit]) when a field loses focus, on Ctrl+S, when the app goes to the background
 * and when the document is left; every other action the panel offers is written at once through
 * [writes]. A status line under the panel says what the draft is doing, and offers Undo once a save
 * landed.
 */
@Composable
internal fun DocumentEditor(
    state: MetadataUiState,
    writes: DocumentWrites,
    preview: @Composable (Modifier) -> Unit,
    /**
     * Where the status line's "mark reviewed" button goes once the document is saved as reviewed: the
     * next document, or away when [hasNext] is false. Offered only where the reviewed marker is real.
     */
    onReviewNext: () -> Unit,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
) {
    val item = state.document.item
    if (item == null) {
        // Trashed here, or deleted on another replica while the panel was open: say so rather
        // than leaving the surface empty.
        DocumentUnavailable(modifier)
        return
    }
    val docId = item.doc.docId
    val store = LocalDocumentDrafts.current
    // Snapshot state, read directly: a keystroke committed through [store] must be visible to the
    // very next composition, which a flow's later dispatch cannot guarantee.
    val draft = store.state[docId] ?: DocumentDraft()
    val edited = draft.overlay(item)
    // What the library holds, the type each key is edited as (an unregistered key is text) and the
    // title, kept per document: leaving one for the next disposes the old document's effect after
    // this composition moved on, and it must still save the old document with the old values.
    val persisted = key(docId) { rememberUpdatedState(item.doc.fields) }
    val types = key(docId) { rememberUpdatedState(state.document.fieldDefs.associate { it.key to it.type }) }
    val title = key(docId) { rememberUpdatedState(edited.doc.displayTitle) }
    // Tell the store this document is on screen while the editor is composed, so the resume banner
    // never offers to open a draft that is already in front of the user (detail route or preview),
    // and that the user left it once the last editor showing it goes.
    DisposableEffect(docId) {
        store.registerEditing(docId)
        onDispose {
            store.unregisterEditing(docId)
            store.left(docId, persisted.value, title.value)
        }
    }
    // The app going to the background (or this screen under another one) is a way of leaving too,
    // and the last chance before the process may be killed.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { store.commit(docId, persisted.value, title.value) }
    val actions =
        remember(writes, store, docId) { DraftedActions(writes, store, docId, persisted, types, title) }
    Column(modifier.imePadding().saveKeys(actions::commitDraft)) {
        MetadataPanel(
            state = state,
            item = edited,
            refused = draft.refused,
            writeBlocked = draft.saving || !draft.writable,
            actions = actions,
            previewTab = preview,
            modifier = Modifier.fillMaxSize().weight(1f),
        )
        EditorStatusBar(
            draft = draft,
            onRetry = actions::commitDraft,
            onUndo = { store.undo(docId) },
            onDiscard = { store.discard(docId) },
            onReview =
                if (state.document.reviewMode.reviewMarkerIsAuthoritative) {
                    { store.commit(docId, persisted.value, title.value, reviewed = true, onSaved = onReviewNext) }
                } else {
                    null
                },
            hasNext = hasNext,
        )
    }
}

/**
 * The panel's actions for one document: field values and the review flag go into [store]'s draft
 * for it, measured against the [persisted] values, and everything else goes straight to [writes].
 * [title] is the document's, as its draft shows it, for the notice a save gives.
 */
internal class DraftedActions(
    private val writes: DocumentWrites,
    private val store: DocumentDrafts,
    private val docId: String,
    private val persisted: State<Map<String, String>>,
    private val types: State<Map<String, FieldType>>,
    private val title: State<String>,
) : MetadataActions,
    DocumentWrites by writes {
    override fun commitField(
        key: String,
        entry: FieldParse,
    ) {
        val type = types.value[key] ?: FieldType.TEXT
        when (entry) {
            is FieldParse.Ok -> store.field(docId, key, entry.canonical, persisted.value[key], type)
            is FieldParse.Invalid -> store.field(docId, key, entry.raw, persisted.value[key], type, refused = true)
        }
    }

    override fun commitDraft() = store.commit(docId, persisted.value, title.value)

    // Removing a field is a finished edit, like a field losing focus.
    override fun removeField(key: String) {
        store.field(docId, key, null, persisted.value[key])
        commitDraft()
    }

    // Saves the draft along with the flag, like the status line's "reviewed" button.
    override fun setReviewed(reviewed: Boolean) = store.commit(docId, persisted.value, title.value, reviewed = reviewed)

    // Once the suggestion lands, the store drops the draft it replaces. A failed write keeps it, and
    // typing during the write is a later edit that the accepted suggestion must not discard.
    override fun acceptAiMetadata(
        key: String,
        onStart: (String) -> Boolean,
        onFinished: (Boolean) -> Unit,
    ) = writes.acceptAiMetadata(
        key,
        onStart = { value -> onStart(value) && store.beginAcceptance(docId, key, value) },
        onFinished = { accepted ->
            store.finishAcceptance(docId, accepted)
            onFinished(accepted)
        },
    )
}

/**
 * What the document's draft is doing, under the panel, and the "mark reviewed" button. Shown only
 * when there is something to say: a draft that cannot be written yet (with Discard), a save that
 * failed (with Retry and Discard), a save running, or one that landed (with Undo). A draft the
 * editor will save by itself says nothing.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorStatusBar(
    draft: DocumentDraft,
    onRetry: () -> Unit,
    onUndo: () -> Unit,
    onDiscard: () -> Unit,
    onReview: (() -> Unit)?,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    val status =
        when {
            !draft.writable -> stringResource(Res.string.detail_draft_invalid)
            draft.error != null -> stringResource(Res.string.ux_save_failed_summary)
            draft.saving -> stringResource(Res.string.detail_saving)
            draft.undo != null || draft.saved -> stringResource(Res.string.detail_saved)
            else -> null
        }
    if (status == null && onReview == null) return
    val problem = draft.error != null || !draft.writable
    val statusColor = if (problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier) {
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                status?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = statusColor,
                        // Announce save progress and outcome to assistive tech as it changes; an error is
                        // assertive so it interrupts, the rest are polite. The text itself is the message.
                        modifier =
                            Modifier.semantics {
                                liveRegion =
                                    if (draft.error != null) LiveRegionMode.Assertive else LiveRegionMode.Polite
                            },
                    )
                }
                if (draft.undo != null && !draft.saving) {
                    TextButton(onClick = onUndo) { Text(stringResource(Res.string.library_undo)) }
                }
                if (draft.error != null && draft.writable) {
                    TextButton(
                        onClick = onRetry,
                        enabled = !draft.saving,
                    ) { Text(stringResource(Res.string.common_retry)) }
                }
                if (problem && draft.dirty) {
                    TextButton(onClick = { confirmDiscard = true }, enabled = !draft.saving) {
                        Text(stringResource(Res.string.ux_discard_short))
                    }
                }
            }
            if (draft.error != null) TechnicalDetails(draft.error)
            onReview?.let {
                Button(onClick = it, enabled = !draft.saving && draft.writable) {
                    Text(stringResource(if (hasNext) Res.string.ux_review_next else Res.string.ux_review_finish))
                }
            }
        }
        if (confirmDiscard) {
            ConfirmDialog(
                title = stringResource(Res.string.ux_discard),
                message = stringResource(Res.string.ux_discard_prompt),
                confirmLabel = stringResource(Res.string.ux_discard),
                onConfirm = {
                    confirmDiscard = false
                    onDiscard()
                },
                onDismiss = { confirmDiscard = false },
            )
        }
    }
}
