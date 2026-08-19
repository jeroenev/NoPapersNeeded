package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.npn.app.ui.common.NumericField
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.UiTextKey
import dev.npn.app.ui.common.WideLayoutBreakpoint
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiField
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.app.AiTagging
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.EditScope
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_all_documents
import no_papers_needed.composeapp.generated.resources.ai_edit_allow_new_tags
import no_papers_needed.composeapp.generated.resources.ai_edit_allow_new_tags_help
import no_papers_needed.composeapp.generated.resources.ai_edit_background
import no_papers_needed.composeapp.generated.resources.ai_edit_background_help
import no_papers_needed.composeapp.generated.resources.ai_edit_custom_prompt
import no_papers_needed.composeapp.generated.resources.ai_edit_custom_prompt_hint
import no_papers_needed.composeapp.generated.resources.ai_edit_documents_per_batch
import no_papers_needed.composeapp.generated.resources.ai_edit_fields
import no_papers_needed.composeapp.generated.resources.ai_edit_fields_help
import no_papers_needed.composeapp.generated.resources.ai_edit_intro
import no_papers_needed.composeapp.generated.resources.ai_edit_new_tags_per_document
import no_papers_needed.composeapp.generated.resources.ai_edit_new_tags_range
import no_papers_needed.composeapp.generated.resources.ai_edit_options
import no_papers_needed.composeapp.generated.resources.ai_edit_overwrite
import no_papers_needed.composeapp.generated.resources.ai_edit_range
import no_papers_needed.composeapp.generated.resources.ai_edit_review_queue
import no_papers_needed.composeapp.generated.resources.ai_edit_saved_view_choose
import no_papers_needed.composeapp.generated.resources.ai_edit_scope
import no_papers_needed.composeapp.generated.resources.ai_edit_scope_missing_fields
import no_papers_needed.composeapp.generated.resources.ai_edit_scope_saved_view
import no_papers_needed.composeapp.generated.resources.ai_edit_scope_selection
import no_papers_needed.composeapp.generated.resources.ai_edit_start
import no_papers_needed.composeapp.generated.resources.ai_edit_start_background
import no_papers_needed.composeapp.generated.resources.ai_edit_suggest_tag_removals
import no_papers_needed.composeapp.generated.resources.ai_edit_suggest_tag_removals_help
import no_papers_needed.composeapp.generated.resources.ai_edit_suggest_tags
import no_papers_needed.composeapp.generated.resources.ai_edit_suggest_tags_help
import no_papers_needed.composeapp.generated.resources.ai_edit_tags
import no_papers_needed.composeapp.generated.resources.ai_edit_tags_per_document
import no_papers_needed.composeapp.generated.resources.ai_edit_untagged_documents
import org.jetbrains.compose.resources.stringResource

/** Widest the setup form grows to; beyond this the two columns are centred instead of stretched. */
private val SetupMaxWidth = 1_100.dp

/**
 * The Idle phase of the AI-edit page: pick the document scope, the tag behaviour, which fields to
 * autofill (and whether each may overwrite a filled value), free-text instructions and the per-run
 * numeric overrides.
 *
 * Wide windows split the sections over two columns so the form fills the desktop width instead of
 * one narrow ribbon; narrow windows stack them. Dependent controls (the tag caps, a field's
 * overwrite switch) only render once the option they belong to is on.
 */
@Composable
internal fun AiEditSetup(
    ui: AiEditUiState,
    vm: AiEditViewModel,
    content: DocContentFacade,
) {
    // Collected here rather than in AiEditScreen so the (possibly library-wide) scope query only
    // runs while this phase is on screen.
    val preview by vm.preview.collectAsStateWithLifecycle()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth > WideLayoutBreakpoint
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(
                Modifier.widthIn(max = SetupMaxWidth),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(Res.string.ai_edit_intro), style = MaterialTheme.typography.titleMedium)
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        SetupColumn(Modifier.weight(1f)) {
                            ScopeSection(ui, preview, vm, content)
                            FieldSection(ui, vm)
                        }
                        SetupColumn(Modifier.weight(1f)) {
                            TagSection(ui, vm)
                            PromptField(ui, vm)
                            RunSection(ui, preview.total, vm)
                        }
                    }
                } else {
                    ScopeSection(ui, preview, vm, content)
                    TagSection(ui, vm)
                    FieldSection(ui, vm)
                    PromptField(ui, vm)
                    RunSection(ui, preview.total, vm)
                }
                StartButton(ui, preview.total, vm)
            }
        }
    }
}

@Composable
private fun SetupColumn(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp), content = content)

/**
 * Which documents the run targets. Unavailable scopes (no library selection handed over, no inbox
 * workflow, no saved views, no autofill field enabled) are hidden rather than shown disabled.
 */
@Composable
private fun ScopeSection(
    ui: AiEditUiState,
    preview: ScopePreview,
    vm: AiEditViewModel,
    content: DocContentFacade,
) {
    SetupSection(stringResource(Res.string.ai_edit_scope)) {
        EditScope.entries.filter { ui.scopeAvailable(it) }.forEach { scope ->
            ScopeOption(scope, scopeLabel(scope, ui), ui.scope, vm::setScope)
            if (scope == EditScope.SAVED_VIEW && ui.scope == EditScope.SAVED_VIEW) {
                SavedViewPicker(ui, vm)
            }
        }
        BatchPreview(preview, ui.batchSize, content)
    }
}

@Composable
private fun scopeLabel(
    scope: EditScope,
    ui: AiEditUiState,
): String =
    when (scope) {
        EditScope.SELECTION -> stringResource(Res.string.ai_edit_scope_selection, ui.selectionSize)
        EditScope.UNTAGGED -> stringResource(Res.string.ai_edit_untagged_documents)
        EditScope.REVIEW_QUEUE -> stringResource(Res.string.ai_edit_review_queue)
        EditScope.MISSING_FIELDS -> stringResource(Res.string.ai_edit_scope_missing_fields)
        EditScope.SAVED_VIEW -> stringResource(Res.string.ai_edit_scope_saved_view)
        EditScope.ALL -> stringResource(Res.string.ai_edit_all_documents)
    }

/** Dropdown of stored library views, indented under the saved-view scope it parametrizes. */
@Composable
private fun SavedViewPicker(
    ui: AiEditUiState,
    vm: AiEditViewModel,
) {
    var expanded by remember { mutableStateOf(false) }
    val current = ui.savedViews.firstOrNull { it.id == ui.savedViewId }
    Column(Modifier.padding(start = 48.dp)) {
        OutlinedButton(onClick = { expanded = true }) {
            Text(current?.name ?: stringResource(Res.string.ai_edit_saved_view_choose))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ui.savedViews.forEach { view ->
                DropdownMenuItem(
                    text = { Text(view.name) },
                    onClick = {
                        vm.setSavedView(view.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Tag additions (with their caps) and tag removals; each cap follows the switch it belongs to. */
@Composable
private fun TagSection(
    ui: AiEditUiState,
    vm: AiEditViewModel,
) {
    SetupSection(stringResource(Res.string.ai_edit_tags)) {
        SwitchRow(
            label = stringResource(Res.string.ai_edit_suggest_tags),
            help = stringResource(Res.string.ai_edit_suggest_tags_help),
            checked = ui.includeTags,
            onCheckedChange = vm::setIncludeTags,
        )
        if (ui.includeTags) {
            NumericField(
                value = ui.maxTags,
                onValueChange = vm::setMaxTags,
                range = AiTagging.RANGE,
                label = stringResource(Res.string.ai_edit_tags_per_document),
                supportingText =
                    stringResource(
                        Res.string.ai_edit_range,
                        AiTagging.MIN_TAGS_PER_DOC,
                        AiTagging.MAX_TAGS_PER_DOC,
                    ),
                maxDigits = 3,
            )
            SwitchRow(
                label = stringResource(Res.string.ai_edit_allow_new_tags),
                help = stringResource(Res.string.ai_edit_allow_new_tags_help),
                checked = ui.allowNewTags,
                onCheckedChange = vm::setAllowNewTags,
            )
        }
        // The cap only means anything while brand-new tags are allowed at all.
        if (ui.includeTags && ui.allowNewTags) {
            NumericField(
                value = ui.maxNewTags,
                onValueChange = vm::setMaxNewTags,
                range = AiTagging.NEW_TAGS_RANGE,
                label = stringResource(Res.string.ai_edit_new_tags_per_document),
                supportingText =
                    stringResource(
                        Res.string.ai_edit_new_tags_range,
                        AiTagging.MIN_NEW_TAGS_PER_DOC,
                        AiTagging.MAX_NEW_TAGS_PER_DOC,
                        AiTagging.MIN_NEW_TAGS_PER_DOC,
                    ),
                maxDigits = 3,
            )
        }
        SwitchRow(
            label = stringResource(Res.string.ai_edit_suggest_tag_removals),
            help = stringResource(Res.string.ai_edit_suggest_tag_removals_help),
            checked = ui.includeTagRemovals,
            onCheckedChange = vm::setIncludeTagRemovals,
        )
    }
}

@Composable
private fun FieldSection(
    ui: AiEditUiState,
    vm: AiEditViewModel,
) {
    SetupSection(stringResource(Res.string.ai_edit_fields)) {
        Text(
            stringResource(Res.string.ai_edit_fields_help),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        AiField.entries.forEach { field ->
            FieldOption(
                label = fieldLabel(field),
                option = ui.fieldOptions[field] ?: AiFieldOption(),
                onEnabledChange = { vm.setFieldEnabled(field, it) },
                onOverwriteChange = { vm.setFieldMode(field, fieldMode(it)) },
            )
        }
    }
}

@Composable
private fun PromptField(
    ui: AiEditUiState,
    vm: AiEditViewModel,
) {
    OutlinedTextField(
        value = ui.customPrompt,
        onValueChange = vm::setCustomPrompt,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(Res.string.ai_edit_custom_prompt)) },
        supportingText = { Text(stringResource(Res.string.ai_edit_custom_prompt_hint)) },
        minLines = 2,
    )
}

/**
 * Where the run executes, and — while it executes here — how much of the scope one batch takes.
 *
 * The batch cap only exists to keep a Review a human can sit through in one go, so it is hidden
 * for a background run: that one covers the whole scope, because nobody is waiting on it and
 * splitting it would only make the user come back for the rest.
 */
@Composable
private fun RunSection(
    ui: AiEditUiState,
    count: Int,
    vm: AiEditViewModel,
) {
    val background = ui.runsInBackground(count)
    SetupSection(stringResource(Res.string.ai_edit_options)) {
        SwitchRow(
            label = stringResource(Res.string.ai_edit_background),
            help = stringResource(Res.string.ai_edit_background_help),
            checked = background,
            onCheckedChange = vm::setBackground,
        )
        if (!background) {
            NumericField(
                value = ui.batchSize,
                onValueChange = vm::setBatchSize,
                range = AiTagging.BATCH_RANGE,
                label = stringResource(Res.string.ai_edit_documents_per_batch),
                supportingText =
                    stringResource(
                        Res.string.ai_edit_range,
                        AiTagging.MIN_BATCH_DOCS,
                        AiTagging.MAX_BATCH_DOCS,
                    ),
                maxDigits = 3,
            )
        }
    }
}

@Composable
private fun StartButton(
    ui: AiEditUiState,
    count: Int,
    vm: AiEditViewModel,
) {
    // Nothing requested means there is nothing to ask the model for, so Start stays inert.
    Button(onClick = vm::start, enabled = count > 0 && ui.hasEdits()) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            stringResource(
                if (ui.runsInBackground(count)) Res.string.ai_edit_start_background else Res.string.ai_edit_start,
            ),
            Modifier.padding(start = 8.dp),
        )
    }
}

/** Header + stacked full-width controls; the shared shape of every Idle-phase group. */
@Composable
private fun SetupSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

/**
 * One autofill field on a single row: enable checkbox, field name, and — once enabled — a compact
 * overwrite switch. A switch (rather than a segmented All/Only-if-empty control) keeps the row on
 * one line in every language.
 */
@Composable
private fun FieldOption(
    label: String,
    option: AiFieldOption,
    onEnabledChange: (Boolean) -> Unit,
    onOverwriteChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = option.enabled) { onEnabledChange(!option.enabled) },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Checkbox(checked = option.enabled, onCheckedChange = onEnabledChange)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        if (option.enabled) {
            Text(
                stringResource(Res.string.ai_edit_overwrite),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Switch(checked = option.mode == AiFieldMode.ALL, onCheckedChange = onOverwriteChange)
        }
    }
}

/** Label (+ optional help) on the left, a yes/no [Switch] on the right. */
@Composable
private fun SwitchRow(
    label: String,
    help: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            help?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ScopeOption(
    scope: EditScope,
    label: String,
    selected: EditScope,
    onSelect: (EditScope) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = scope == selected) { onSelect(scope) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = scope == selected, onClick = { onSelect(scope) })
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun fieldMode(overwrite: Boolean): AiFieldMode = if (overwrite) AiFieldMode.ALL else AiFieldMode.UNFILLED

/** "N documents" or, when the scope outgrows one run, "N documents, first M this batch". */
internal fun scopeCountText(
    count: Int,
    batchSize: Int,
): UiText =
    if (count > batchSize) {
        uiText(UiTextKey.TAGGING_SCOPE_COUNT_BATCH, count, batchSize)
    } else {
        uiText(UiTextKey.TAGGING_SCOPE_COUNT, count)
    }
