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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.TechnicalDetails
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.reviewMarkerIsAuthoritative
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_saved
import no_papers_needed.composeapp.generated.resources.detail_saving
import no_papers_needed.composeapp.generated.resources.ux_discard
import no_papers_needed.composeapp.generated.resources.ux_discard_prompt
import no_papers_needed.composeapp.generated.resources.ux_discard_short
import no_papers_needed.composeapp.generated.resources.ux_draft_hint
import no_papers_needed.composeapp.generated.resources.ux_field_invalid
import no_papers_needed.composeapp.generated.resources.ux_review_finish
import no_papers_needed.composeapp.generated.resources.ux_review_next
import no_papers_needed.composeapp.generated.resources.ux_save_changes
import no_papers_needed.composeapp.generated.resources.ux_save_failed_summary
import org.jetbrains.compose.resources.stringResource

/**
 * One explicit save model for all editable document fields, in either responsive presentation.
 * Field values and the review flag go into the document's draft and wait for Save; every other
 * action the panel offers is written at once through [writes].
 */
@Composable
internal fun DocumentEditor(
    state: MetadataUiState,
    writes: DocumentWrites,
    preview: @Composable (Modifier) -> Unit,
    /**
     * Where the save bar's "mark reviewed" button goes once the document is saved as reviewed: the
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
    // Tell the store this document is on screen while the editor is composed, so the resume banner
    // never offers to open a draft that is already in front of the user (detail route or preview).
    DisposableEffect(docId) {
        store.registerEditing(docId)
        onDispose { store.unregisterEditing(docId) }
    }
    // The draft judges validity by the type each key was edited as; an unregistered key is text.
    val persisted = rememberUpdatedState(item.doc.fields)
    val types = rememberUpdatedState(state.document.fieldDefs.associate { it.key to it.type })
    val actions = remember(writes, store, docId) { DraftedActions(writes, store, docId, persisted, types) }
    Column(modifier.imePadding().saveKeys { if (draft.dirty) store.save(docId) }) {
        MetadataPanel(
            state = state,
            item = edited,
            refused = draft.refused,
            writeBlocked = draft.saving || !draft.writable,
            actions = actions,
            previewTab = preview,
            modifier = Modifier.fillMaxSize().weight(1f),
        )
        EditorSaveBar(
            draft = draft,
            onSave = { store.save(docId) },
            onDiscard = { store.discard(docId) },
            onReview =
                if (state.document.reviewMode.reviewMarkerIsAuthoritative) {
                    { store.save(docId, reviewed = true, onSaved = onReviewNext) }
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
 */
internal class DraftedActions(
    private val writes: DocumentWrites,
    private val store: DocumentDrafts,
    private val docId: String,
    private val persisted: State<Map<String, String>>,
    private val types: State<Map<String, FieldType>>,
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

    override fun removeField(key: String) = store.field(docId, key, null, persisted.value[key])

    // Saves the draft along with the flag, like the save bar's "reviewed" button.
    override fun setReviewed(reviewed: Boolean) = store.save(docId, reviewed = reviewed)

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorSaveBar(
    draft: DocumentDraft,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onReview: (() -> Unit)?,
    hasNext: Boolean,
    modifier: Modifier = Modifier,
) {
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    Column(modifier) {
        HorizontalDivider()
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            val status =
                when {
                    !draft.writable -> stringResource(Res.string.ux_field_invalid)
                    draft.error != null -> stringResource(Res.string.ux_save_failed_summary)
                    draft.saving -> stringResource(Res.string.detail_saving)
                    draft.dirty -> stringResource(Res.string.ux_draft_hint)
                    draft.saved -> stringResource(Res.string.detail_saved)
                    else -> null
                }
            status?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (draft.error != null || !draft.writable) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    // Announce save progress and outcome to assistive tech as it changes; an error is
                    // assertive so it interrupts, the rest are polite. The text itself is the message.
                    modifier =
                        Modifier.semantics {
                            liveRegion =
                                if (draft.error != null) LiveRegionMode.Assertive else LiveRegionMode.Polite
                        },
                )
            }
            if (draft.error != null) TechnicalDetails(draft.error)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (draft.dirty || draft.saving) {
                    Button(onClick = onSave, enabled = !draft.saving && draft.writable) {
                        Text(stringResource(Res.string.ux_save_changes))
                    }
                    TextButton(onClick = { confirmDiscard = true }, enabled = !draft.saving) {
                        Text(stringResource(Res.string.ux_discard_short))
                    }
                }
                onReview?.let {
                    Button(onClick = it, enabled = !draft.saving && draft.writable) {
                        Text(stringResource(if (hasNext) Res.string.ux_review_next else Res.string.ux_review_finish))
                    }
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
