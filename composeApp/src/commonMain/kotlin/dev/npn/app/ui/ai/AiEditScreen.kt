@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.AddCircleOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.DocThumbnail
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.app.ui.common.TagSuggestionLabel
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.documentDisplayTitle
import dev.npn.app.ui.common.resolve
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiField
import dev.npn.core.app.ApplyResult
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.EditProgress
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReviewDoc
import dev.npn.core.app.ReviewField
import dev.npn.core.app.SettingsRepository
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_collapse
import no_papers_needed.composeapp.generated.resources.ai_edit_applying
import no_papers_needed.composeapp.generated.resources.ai_edit_clear_selection
import no_papers_needed.composeapp.generated.resources.ai_edit_done
import no_papers_needed.composeapp.generated.resources.ai_edit_next_batch
import no_papers_needed.composeapp.generated.resources.ai_edit_no_suggestions
import no_papers_needed.composeapp.generated.resources.ai_edit_remove_tags_label
import no_papers_needed.composeapp.generated.resources.ai_edit_replaces
import no_papers_needed.composeapp.generated.resources.ai_edit_select_all
import no_papers_needed.composeapp.generated.resources.ai_edit_skip
import no_papers_needed.composeapp.generated.resources.ai_edit_suggesting_progress
import no_papers_needed.composeapp.generated.resources.ai_edit_suggestions_selected
import no_papers_needed.composeapp.generated.resources.ai_edit_title
import no_papers_needed.composeapp.generated.resources.ai_expand
import no_papers_needed.composeapp.generated.resources.common_apply
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.detail_correspondent
import no_papers_needed.composeapp.generated.resources.detail_document_date_plain
import no_papers_needed.composeapp.generated.resources.detail_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun AiEditScreen(
    session: LibrarySession,
    settings: SettingsRepository,
    onBack: () -> Unit,
    selection: List<String> = emptyList(),
) {
    val vm: AiEditViewModel = viewModel { AiEditViewModel(session, settings, selection) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ai_edit_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(Res.string.common_back),
                        )
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when (val phase = ui.phase) {
                is EditPhase.Idle -> AiEditSetup(ui, vm, session.content)
                is EditPhase.Suggesting -> SuggestingPhase(phase.progress, vm::cancel)
                is EditPhase.Review -> ReviewPhase(phase, vm, session.content, vm::skipReview)
                is EditPhase.Applying -> CenteredProgress(stringResource(Res.string.ai_edit_applying))
                is EditPhase.Done -> DonePhase(phase.result, vm::nextBatch, onBack)
            }
        }
    }
}

// ---- Suggesting: progress ----

@Composable
private fun SuggestingPhase(
    progress: EditProgress,
    onCancel: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            stringResource(
                Res.string.ai_edit_suggesting_progress,
                progress.done + 1,
                progress.total,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        val fraction = if (progress.total == 0) 0f else progress.done.toFloat() / progress.total
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text(
            documentDisplayTitle(progress.currentTitle, progress.currentFileName),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
    }
}

// ---- Review: per-doc chips + field rows + apply bar ----

@Composable
private fun ReviewPhase(
    phase: EditPhase.Review,
    vm: AiEditViewModel,
    content: DocContentFacade,
    onSkip: () -> Unit,
) {
    val selected =
        phase.docs.sumOf { doc ->
            doc.chips.count { it.selected } + doc.removals.count { it.selected } + doc.fields.count { it.selected }
        }
    val total =
        phase.docs.sumOf { doc -> doc.chips.size + doc.removals.size + doc.fields.size }
    val docsWithSelection =
        phase.docs.count { doc ->
            doc.chips.any { it.selected } || doc.removals.any { it.selected } || doc.fields.any { it.selected }
        }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { BatchSelectionHeader(selected, total, vm::setAllSelection) }
            items(phase.docs, key = { it.docId }) { doc ->
                ReviewRow(doc, content, vm::toggleChip, vm::toggleField, vm::setDocSelection)
            }
            if (phase.emptyDocs.isNotEmpty()) {
                item { NoSuggestionsSection(phase.emptyDocs) }
            }
        }
        ApplyBar(selected, docsWithSelection, vm::apply, onSkip)
    }
}

@Composable
private fun BatchSelectionHeader(
    selected: Int,
    total: Int,
    onSetAll: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            pluralStringResource(Res.plurals.ai_edit_suggestions_selected, total, selected, total),
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { onSetAll(true) }) {
            Text(stringResource(Res.string.ai_edit_select_all))
        }
        TextButton(onClick = { onSetAll(false) }) {
            Text(stringResource(Res.string.ai_edit_clear_selection))
        }
    }
}

@Composable
private fun ReviewRow(
    doc: ReviewDoc,
    content: DocContentFacade,
    onToggle: (String, String) -> Unit,
    onToggleField: (String, AiField) -> Unit,
    onSetDoc: (String, Boolean) -> Unit,
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
        DocThumbnail(docId = doc.docId, content = content, size = 56.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(documentDisplayTitle(doc.title, doc.fileName), style = MaterialTheme.typography.titleSmall)
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
                AddChipsRow(doc, onToggle)
            }
            if (doc.removals.isNotEmpty()) {
                RemovalChipsRow(doc, onToggle)
            }
            doc.fields.forEach { field ->
                ReviewFieldRow(field, onToggle = { onToggleField(doc.docId, field.field) })
            }
        }
        TriStateCheckbox(
            state = toggleState,
            // On/Indeterminate → clear; Off → select all.
            onClick = { onSetDoc(doc.docId, !allSelected) },
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
                    Icon(
                        Icons.Outlined.AddCircleOutline,
                        contentDescription = null,
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
                        contentDescription = null,
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
        Modifier.fillMaxWidth().selectable(selected = field.selected) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = field.selected, onCheckedChange = { onToggle() })
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(
                fieldLabel(field.field),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                field.value,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            field.current?.let {
                Text(
                    stringResource(Res.string.ai_edit_replaces, it),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Localized label of a single-value document field; shared with the Idle-phase setup UI. */
@Composable
internal fun fieldLabel(field: AiField): String =
    when (field) {
        AiField.TITLE -> stringResource(Res.string.detail_title)
        AiField.CORRESPONDENT -> stringResource(Res.string.detail_correspondent)
        AiField.DOC_DATE -> stringResource(Res.string.detail_document_date_plain)
    }

@Composable
private fun NoSuggestionsSection(docs: List<ReviewDoc>) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider()
        Row(
            Modifier
                .fillMaxWidth()
                .selectable(selected = expanded) { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
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
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription =
                    stringResource(
                        if (expanded) Res.string.ai_collapse else Res.string.ai_expand,
                    ),
            )
        }
        if (expanded) {
            docs.forEach { doc ->
                Text(
                    documentDisplayTitle(doc.title, doc.fileName),
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
    onApply: () -> Unit,
    onSkip: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(applySummary(selectedSuggestions, docCount), Modifier.weight(1f))
            // Escape hatch: leave Review without writing (also the only way forward when nothing
            // is selected — all suggestions new/deselected or every doc had no suggestions).
            OutlinedButton(onClick = onSkip) { Text(stringResource(Res.string.ai_edit_skip)) }
            Button(onClick = onApply, enabled = selectedSuggestions > 0) {
                Text(stringResource(Res.string.common_apply))
            }
        }
    }
}

@Composable
private fun applySummary(
    suggestions: Int,
    docs: Int,
): String = uiText(UiTextKey.TAGGING_APPLY_SUMMARY, suggestions, docs).resolve()

// ---- Done + shared ----

@Composable
private fun DonePhase(
    result: ApplyResult,
    onNextBatch: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(doneSummary(result), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (result.moreRemain) {
            Button(onClick = onNextBatch) { Text(stringResource(Res.string.ai_edit_next_batch)) }
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
private fun CenteredProgress(label: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
