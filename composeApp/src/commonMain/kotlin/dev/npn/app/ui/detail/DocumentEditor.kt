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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.core.ai.AiField
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
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
import no_papers_needed.composeapp.generated.resources.ux_save_failed
import org.jetbrains.compose.resources.stringResource

/**
 * True while this document cannot be written: a save is in flight, or the draft is not writable
 * yet (an unparseable date). Only controls that *trigger* a write read it — editing itself stays
 * live, so a save in progress never disables the field the user is typing in.
 */
internal val LocalDocumentWriteBlocked = staticCompositionLocalOf { false }

/** One explicit save model for all editable document fields, in either responsive presentation. */
@Composable
internal fun DocumentEditor(
    state: MetadataUiState,
    actions: MetadataActions,
    preview: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    onReviewed: (() -> Unit)? = null,
    hasNext: Boolean = false,
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
    val types = state.document.fieldDefs.associate { it.key to it.type }
    val editActions =
        actions.edit.copy(
            onSetReviewed = { store.save(docId, reviewed = it) },
            onCommitField = { key, value ->
                store.field(docId, key, value, item.doc.fields[key], types[key] ?: FieldType.TEXT)
            },
            onRemoveField = { store.field(docId, it, null, item.doc.fields[it]) },
        )
    val aiActions =
        actions.ai.copy(
            // Accepting a suggestion saves immediately (the "saved" notice says so), but any draft
            // the user typed for that field must be dropped first — otherwise overlay would keep
            // showing the typed value and the next save would overwrite the accepted suggestion.
            onAcceptMetadata = { field ->
                store.clearField(docId, field.draftKey())
                actions.ai.onAcceptMetadata(field)
            },
        )
    Column(
        modifier.imePadding().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.S) {
                if (draft.dirty) store.save(docId)
                true
            } else {
                false
            }
        },
    ) {
        CompositionLocalProvider(LocalDocumentWriteBlocked provides (draft.saving || !draft.writable)) {
            MetadataPanel(
                state = state.copy(document = state.document.copy(item = edited)),
                actions = actions.copy(edit = editActions, ai = aiActions),
                previewTab = preview,
                modifier = Modifier.fillMaxSize().weight(1f),
            )
        }
        EditorSaveBar(
            draft = draft,
            onSave = { store.save(docId) },
            onDiscard = { store.discard(docId) },
            onReviewed =
                onReviewed?.takeIf { state.document.reviewMode == ReviewMode.INBOX_WORKFLOW }?.let { next ->
                    { store.save(docId, reviewed = true, onSaved = next) }
                },
            hasNext = hasNext,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorSaveBar(
    draft: DocumentDraft,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onReviewed: (() -> Unit)?,
    hasNext: Boolean,
) {
    var confirmDiscard by remember { mutableStateOf(false) }
    HorizontalDivider()
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        val status =
            when {
                !draft.writable -> stringResource(Res.string.ux_field_invalid)
                draft.error != null -> stringResource(Res.string.ux_save_failed, draft.error)
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
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (draft.dirty || draft.saving) {
                Button(onClick = onSave, enabled = !draft.saving && draft.writable) {
                    Text(stringResource(Res.string.ux_save_changes))
                }
                TextButton(onClick = { confirmDiscard = true }, enabled = !draft.saving) {
                    Text(stringResource(Res.string.ux_discard_short))
                }
            }
            onReviewed?.let {
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

/** The draft property key an AI-suggestable field maps to. Total, so a new [AiField] must map here. */
private fun AiField.draftKey(): String =
    when (this) {
        AiField.TITLE -> FieldKeys.TITLE
        AiField.CORRESPONDENT -> FieldKeys.CORRESPONDENT
        AiField.DOC_DATE -> FieldKeys.DOC_DATE
    }
