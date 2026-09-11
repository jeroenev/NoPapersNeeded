@file:OptIn(ExperimentalLayoutApi::class)

package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.app.ui.common.TagSuggestionLabel
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.resolve
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.ApplyResult
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.ReviewDoc
import dev.npn.core.app.ReviewField
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_add_tag
import no_papers_needed.composeapp.generated.resources.ai_edit_attention
import no_papers_needed.composeapp.generated.resources.ai_edit_change_settings
import no_papers_needed.composeapp.generated.resources.ai_edit_clear_selection
import no_papers_needed.composeapp.generated.resources.ai_edit_discard_run
import no_papers_needed.composeapp.generated.resources.ai_edit_discard_run_message
import no_papers_needed.composeapp.generated.resources.ai_edit_discard_run_title
import no_papers_needed.composeapp.generated.resources.ai_edit_done
import no_papers_needed.composeapp.generated.resources.ai_edit_next_batch
import no_papers_needed.composeapp.generated.resources.ai_edit_no_suggestions
import no_papers_needed.composeapp.generated.resources.ai_edit_open_document
import no_papers_needed.composeapp.generated.resources.ai_edit_remove_tag
import no_papers_needed.composeapp.generated.resources.ai_edit_remove_tags_label
import no_papers_needed.composeapp.generated.resources.ai_edit_replaces
import no_papers_needed.composeapp.generated.resources.ai_edit_select_all
import no_papers_needed.composeapp.generated.resources.ai_edit_select_recommended
import no_papers_needed.composeapp.generated.resources.ai_edit_skip
import no_papers_needed.composeapp.generated.resources.ai_edit_suggestions_selected
import no_papers_needed.composeapp.generated.resources.common_apply
import no_papers_needed.composeapp.generated.resources.common_cancel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Thumbnail size of a review row; big enough to recognise the page behind the suggestions. */
private val ReviewThumbnailSize = 56.dp

/**
 * One batch of suggestions awaiting confirmation: per-document chips and field rows, the
 * select-all header, the documents the model had nothing for, and the apply bar. Nothing is
 * written until [ReviewActions.onApply].
 */
@Composable
internal fun ReviewBatch(
    docs: List<ReviewDoc>,
    emptyDocs: List<ReviewDoc>,
    content: DocContentFacade,
    actions: ReviewActions,
) {
    val selected =
        docs.sumOf { doc ->
            doc.chips.count { it.selected } + doc.removals.count { it.selected } + doc.fields.count { it.selected }
        }
    val total = docs.sumOf { doc -> doc.chips.size + doc.removals.size + doc.fields.size }
    val docsWithSelection =
        docs.count { doc ->
            doc.chips.any { it.selected } || doc.removals.any { it.selected } || doc.fields.any { it.selected }
        }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BatchSelectionHeader(selected, total, docs.attentionCount(), actions) }
            items(docs, key = { it.docId }) { doc ->
                ReviewRow(doc, content, actions)
            }
            if (emptyDocs.isNotEmpty()) {
                item { NoSuggestionsSection(emptyDocs) }
            }
        }
        ApplyBar(selected, docsWithSelection, actions)
    }
}

/**
 * How much of the batch is selected, how much of it needs judgement, and the bulk actions.
 *
 * "Select recommended" leads because it is the only bulk action that respects the pre-select
 * policy: "select all" also ticks invented tags, removals and overwrites, which the projector
 * deliberately left off. A [FlowRow] so three actions plus a summary still fit a phone.
 */
@Composable
private fun BatchSelectionHeader(
    selected: Int,
    total: Int,
    attention: Int,
    actions: ReviewActions,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            pluralStringResource(Res.plurals.ai_edit_suggestions_selected, total, selected, total),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (attention > 0) {
            Text(
                pluralStringResource(Res.plurals.ai_edit_attention, attention, attention),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = actions.onSelectRecommended) {
                Text(stringResource(Res.string.ai_edit_select_recommended))
            }
            TextButton(onClick = { actions.onSetAll(true) }) {
                Text(stringResource(Res.string.ai_edit_select_all))
            }
            TextButton(onClick = { actions.onSetAll(false) }) {
                Text(stringResource(Res.string.ai_edit_clear_selection))
            }
        }
    }
}

@Composable
private fun ReviewRow(
    doc: ReviewDoc,
    content: DocContentFacade,
    actions: ReviewActions,
) {
    val allSelected =
        doc.chips.all { it.selected } && doc.removals.all { it.selected } && doc.fields.all { it.selected }
    val noneSelected =
        doc.chips.none { it.selected } && doc.removals.none { it.selected } && doc.fields.none { it.selected }
    val toggleState =
        when {
            allSelected -> ToggleableState.On
            noneSelected -> ToggleableState.Off
            else -> ToggleableState.Indeterminate
        }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
        DocThumbnail(docId = doc.docId, content = content, size = ReviewThumbnailSize)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(doc.displayTitle, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                // The snippet is two lines of OCR text, which is not enough to judge a date or a
                // correspondent against. This is the way to the page itself; the review survives
                // the trip, because the screen stays on the back stack.
                IconButton(onClick = { actions.onOpenDoc(doc.docId) }) {
                    Icon(
                        Icons.AutoMirrored.Filled.OpenInNew,
                        contentDescription = stringResource(Res.string.ai_edit_open_document),
                    )
                }
            }
            doc.snippet?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (doc.chips.isNotEmpty()) {
                AddChipsRow(doc, actions.onToggleChip)
            }
            if (doc.removals.isNotEmpty()) {
                RemovalChipsRow(doc, actions.onToggleChip)
            }
            doc.fields.forEach { field ->
                ReviewFieldRow(field, onToggle = { actions.onToggleField(doc.docId, field.key) })
            }
        }
        TriStateCheckbox(
            state = toggleState,
            // On/Indeterminate → clear; Off → select all.
            onClick = { actions.onSetDoc(doc.docId, !allSelected) },
        )
    }
}

/** The proposed tag-addition chips of one review row (new tags start pre-deselected). */
@Composable
private fun AddChipsRow(
    doc: ReviewDoc,
    onToggle: (String, String) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        doc.chips.forEach { chip ->
            FilterChip(
                selected = chip.selected,
                onClick = { onToggle(doc.docId, chip.tag) },
                label = { TagSuggestionLabel(tag = chip.tag, isNew = chip.isNew) },
                leadingIcon = {
                    // Named, not decorative: add and remove chips are otherwise identical to a
                    // screen reader, and they are opposite edits to the same tag.
                    Icon(
                        Icons.Outlined.AddCircleOutline,
                        contentDescription = stringResource(Res.string.ai_edit_add_tag),
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                trailingIcon = selectedCheckmark(chip.selected),
            )
        }
    }
}

/** The proposed tag-removal chips of one review row (all start pre-deselected). */
@Composable
private fun RemovalChipsRow(
    doc: ReviewDoc,
    onToggle: (String, String) -> Unit,
) {
    Text(
        stringResource(Res.string.ai_edit_remove_tags_label),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        doc.removals.forEach { chip ->
            FilterChip(
                selected = chip.selected,
                onClick = { onToggle(doc.docId, chip.tag) },
                label = { Text(chip.tag) },
                leadingIcon = {
                    Icon(
                        Icons.Outlined.RemoveCircleOutline,
                        contentDescription = stringResource(Res.string.ai_edit_remove_tag),
                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                    )
                },
                trailingIcon = selectedCheckmark(chip.selected),
            )
        }
    }
}

/** Trailing checkmark shown on a suggestion chip while it is selected, keeping the add/remove icon visible. */
private fun selectedCheckmark(selected: Boolean): (@Composable () -> Unit)? =
    if (selected) {
        {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                modifier = Modifier.size(FilterChipDefaults.IconSize),
            )
        }
    } else {
        null
    }

/** One suggested data-field value: checkbox + label/value, with the replaced value when present. */
@Composable
private fun ReviewFieldRow(
    field: ReviewField,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = field.selected, role = Role.Checkbox, onValueChange = { onToggle() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Null callback: the row is the target, so the checkbox must not be a second one.
        Checkbox(checked = field.selected, onCheckedChange = null)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                field.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                field.value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            // What this would destroy, in the error colour: it is the one suggestion kind that
            // does not add to the library but replaces part of it.
            field.current?.let {
                Text(
                    stringResource(Res.string.ai_edit_replaces, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun NoSuggestionsSection(docs: List<ReviewDoc>) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        ExpanderRow(
            expanded = expanded,
            onToggle = { expanded = !expanded },
            modifier = Modifier.padding(vertical = 8.dp),
        ) {
            Text(
                pluralStringResource(
                    Res.plurals.ai_edit_no_suggestions,
                    docs.size,
                    docs.size,
                ),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            docs.forEach { doc ->
                Text(
                    doc.displayTitle,
                    Modifier.padding(vertical = 2.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ApplyBar(
    selectedSuggestions: Int,
    docCount: Int,
    actions: ReviewActions,
) {
    var confirmingDiscard by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        FlowRow(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                uiText(UiTextKey.TAGGING_APPLY_SUMMARY, selectedSuggestions, docCount).resolve(),
                Modifier.align(Alignment.CenterVertically),
            )
            // Escape hatch: leave Review without writing (also the only way forward when nothing
            // is selected — all suggestions new/deselected or every doc had no suggestions). What
            // that costs differs per flow, so both the label and the confirmation do too.
            OutlinedButton(
                onClick = {
                    when (actions.exit) {
                        ReviewExit.SKIP_BATCH -> actions.onExit()
                        ReviewExit.DISCARD_RUN -> confirmingDiscard = true
                    }
                },
            ) {
                Text(
                    stringResource(
                        when (actions.exit) {
                            ReviewExit.SKIP_BATCH -> Res.string.ai_edit_skip
                            ReviewExit.DISCARD_RUN -> Res.string.ai_edit_discard_run
                        },
                    ),
                )
            }
            Button(onClick = actions.onApply, enabled = selectedSuggestions > 0) {
                Text(stringResource(Res.string.common_apply))
            }
        }
    }
    if (confirmingDiscard) {
        ConfirmDialog(
            title = stringResource(Res.string.ai_edit_discard_run_title),
            message = stringResource(Res.string.ai_edit_discard_run_message),
            confirmLabel = stringResource(Res.string.ai_edit_discard_run),
            dismissLabel = stringResource(Res.string.common_cancel),
            destructive = true,
            onConfirm = {
                confirmingDiscard = false
                actions.onExit()
            },
            onDismiss = { confirmingDiscard = false },
        )
    }
}

/**
 * Terminal counts after Apply. [onNextBatch] is offered only when the flow has a next batch to
 * run — a queued run has no scope left to continue into, so it passes null — and [onReconfigure]
 * only where there is a setup form to go back to.
 */
@Composable
internal fun DonePhase(
    result: ApplyResult,
    onNextBatch: (() -> Unit)?,
    onBack: () -> Unit,
    onReconfigure: (() -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(doneSummary(result), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (result.moreRemain && onNextBatch != null) {
            Button(onClick = onNextBatch) { Text(stringResource(Res.string.ai_edit_next_batch)) }
        }
        // Between batches is exactly when people realise the run asked for the wrong thing, and
        // leaving the page to fix it would forget which documents were already reviewed.
        if (onReconfigure != null) {
            OutlinedButton(onClick = onReconfigure) {
                Text(stringResource(Res.string.ai_edit_change_settings))
            }
        }
        OutlinedButton(onClick = onBack) { Text(stringResource(Res.string.ai_edit_done)) }
    }
}

@Composable
private fun doneSummary(result: ApplyResult): String =
    if (result.docsChanged == 0) {
        uiText(UiTextKey.TAGGING_DONE_EMPTY).resolve()
    } else {
        uiText(UiTextKey.TAGGING_DONE_SUMMARY, result.applied, result.docsChanged).resolve()
    }

@Composable
internal fun CenteredProgress(label: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
