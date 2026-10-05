package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.ExpanderRow
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.NumericField
import dev.npn.app.ui.common.SwitchRow
import dev.npn.core.app.AiTagging
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.EditScope
import dev.npn.core.model.FieldDef
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_add_field
import no_papers_needed.composeapp.generated.resources.ai_edit_advanced
import no_papers_needed.composeapp.generated.resources.ai_edit_advanced_help
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
import no_papers_needed.composeapp.generated.resources.ai_edit_fields_loading
import no_papers_needed.composeapp.generated.resources.ai_edit_intro
import no_papers_needed.composeapp.generated.resources.ai_edit_new_tags_per_document
import no_papers_needed.composeapp.generated.resources.ai_edit_new_tags_range
import no_papers_needed.composeapp.generated.resources.ai_edit_range
import no_papers_needed.composeapp.generated.resources.ai_edit_recommended
import no_papers_needed.composeapp.generated.resources.ai_edit_review_queue
import no_papers_needed.composeapp.generated.resources.ai_edit_run_now
import no_papers_needed.composeapp.generated.resources.ai_edit_run_now_help
import no_papers_needed.composeapp.generated.resources.ai_edit_run_where
import no_papers_needed.composeapp.generated.resources.ai_edit_saved_view_choose
import no_papers_needed.composeapp.generated.resources.ai_edit_scope
import no_papers_needed.composeapp.generated.resources.ai_edit_scope_missing_fields
import no_papers_needed.composeapp.generated.resources.ai_edit_scope_saved_view
import no_papers_needed.composeapp.generated.resources.ai_edit_scope_selection
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

/** Digits a per-run cap can have; every range here is well under a thousand. */
private const val CAP_MAX_DIGITS = 3

/**
 * The Idle phase of the AI-edit page.
 *
 * Task first: until the user says what they came to do, the page shows only the four jobs it can
 * do (see [EditTask]). From then on the form is the same set of controls it always was — scope,
 * tags, autofill fields, free-text instructions — with the per-run numeric limits folded into an
 * "Advanced" disclosure, because a cap between 1 and 40 is a tuning decision and never the reason
 * someone opened this page.
 *
 * Wide windows split the sections over two columns so the form fills the desktop width instead of
 * one narrow ribbon; narrow windows stack them. Dependent controls (the tag caps, a field's mode)
 * only render once the option they belong to is on. Start lives in the pinned [RunBar], not at the
 * end of the scroll.
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
    val plan by vm.plan.collectAsStateWithLifecycle()
    val blocker by vm.startBlocker.collectAsStateWithLifecycle()
    val wide = LocalWindowWidthClass.current.isWide
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                    val task = ui.task
                    if (task == null) {
                        Text(
                            stringResource(Res.string.ai_edit_intro),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        TaskChooser(onPick = vm::setTask)
                    } else {
                        ChosenTask(task, onChange = vm::clearTask)
                        SetupForm(ui, preview, plan, vm, content, wide)
                    }
                }
            }
        }
        RunBar(ui = ui, plan = plan, blocker = blocker, onStart = vm::start)
    }
}

@Composable
private fun SetupForm(
    ui: AiEditUiState,
    preview: ScopePreview,
    plan: RunPlan,
    vm: AiEditViewModel,
    content: DocContentFacade,
    wide: Boolean,
) {
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            SetupColumn(Modifier.weight(1f)) {
                ScopeSection(ui, preview, vm, content)
                FieldSection(ui, vm)
            }
            SetupColumn(Modifier.weight(1f)) {
                TagSection(ui, vm)
                PromptField(ui, vm)
                RunSection(ui, plan, vm)
            }
        }
    } else {
        ScopeSection(ui, preview, vm, content)
        TagSection(ui, vm)
        FieldSection(ui, vm)
        PromptField(ui, vm)
        RunSection(ui, plan, vm)
    }
}

/**
 * Which documents the run targets. Unavailable scopes (no library selection handed over, no inbox
 * workflow, no saved views, no autofill field enabled) are hidden rather than shown disabled.
 *
 * The one exception is the scope currently in force: turning off the last autofill field takes the
 * field-based scope's own precondition away, and dropping the row would leave this section with
 * nothing selected while the run still targets it (selecting nothing, by design — see
 * `AiEditRun.docsFor`). Keeping the row visible is what makes the empty count explainable.
 */
@Composable
private fun ScopeSection(
    ui: AiEditUiState,
    preview: ScopePreview,
    vm: AiEditViewModel,
    content: DocContentFacade,
) {
    SetupSection(stringResource(Res.string.ai_edit_scope)) {
        EditScope.entries.filter { ui.scopeAvailable(it) || it == ui.scope }.forEach { scope ->
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
    val current = ui.savedViews.firstOrNull { it.id == ui.savedViewId }
    Dropdown(
        selected = current?.name ?: stringResource(Res.string.ai_edit_saved_view_choose),
        options = ui.savedViews,
        optionLabel = { it.name },
        onSelect = { vm.setSavedView(it.id) },
        modifier = Modifier.padding(start = 48.dp),
    )
}

/** Tag additions and tag removals; the caps they belong to live under Advanced. */
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
            SwitchRow(
                label = stringResource(Res.string.ai_edit_allow_new_tags),
                help = stringResource(Res.string.ai_edit_allow_new_tags_help),
                checked = ui.allowNewTags,
                onCheckedChange = vm::setAllowNewTags,
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
        HelpText(stringResource(Res.string.ai_edit_fields_help))
        // The registry is the one part of this form that arrives asynchronously, and an empty list
        // reads exactly like a library with no fields, so say which one it is.
        if (!ui.registryLoaded) {
            HelpText(stringResource(Res.string.ai_edit_fields_loading))
            return@SetupSection
        }
        val labels = ui.fields.associate { it.key to it.label }
        // Registry order, so the run's rows read like the document panel; a key the registry no
        // longer lists keeps its row (shown by key) so a configured run is never silently trimmed.
        val order = ui.fields.map { it.key }
        val keys = ui.fieldOptions.keys.sortedBy { order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }
        for (key in keys) {
            FieldOption(
                label = labels[key] ?: key,
                option = ui.fieldOptions.getValue(key),
                onEnabledChange = { vm.setFieldEnabled(key, it) },
                onModeChange = { vm.setFieldMode(key, it) },
            )
        }
        AddFieldPicker(ui.fields.filter { it.key !in ui.fieldOptions }, vm::addField)
    }
}

/** "Add a field to this run": every registered field the run does not list yet. */
@Composable
private fun AddFieldPicker(
    available: List<FieldDef>,
    onAdd: (String) -> Unit,
) {
    if (available.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(Res.string.ai_edit_add_field), modifier = Modifier.padding(start = 8.dp))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            available.forEach { def ->
                DropdownMenuItem(
                    text = { Text(def.label) },
                    onClick = {
                        expanded = false
                        onAdd(def.key)
                    },
                )
            }
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
 * Where the run executes, plus the per-run limits behind a disclosure.
 *
 * The placement is two named choices rather than one switch: the default follows the scope size,
 * so a switch appears to flip itself as the count arrives. Naming the recommendation instead
 * explains the same default without the control moving under the user's hand.
 */
@Composable
private fun RunSection(
    ui: AiEditUiState,
    plan: RunPlan,
    vm: AiEditViewModel,
) {
    SetupSection(stringResource(Res.string.ai_edit_run_where)) {
        RunPlacementOption(
            label = stringResource(Res.string.ai_edit_run_now),
            help = stringResource(Res.string.ai_edit_run_now_help),
            selected = !plan.background,
            recommended = plan.loaded && ui.backgroundOverride == null && !plan.background,
            onSelect = { vm.setBackground(false) },
        )
        RunPlacementOption(
            label = stringResource(Res.string.ai_edit_background),
            help = stringResource(Res.string.ai_edit_background_help),
            selected = plan.background,
            recommended = plan.loaded && ui.backgroundOverride == null && plan.background,
            onSelect = { vm.setBackground(true) },
        )
        AdvancedSection(ui, plan, vm)
    }
}

@Composable
private fun RunPlacementOption(
    label: String,
    help: String,
    selected: Boolean,
    /** Whether this is the option the scope size would pick on its own. */
    recommended: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton) { onSelect() },
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (recommended) {
                Text(
                    stringResource(Res.string.ai_edit_recommended),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            HelpText(help)
        }
    }
}

/**
 * The per-run caps, collapsed by default.
 *
 * The batch cap only exists to keep a Review a human can sit through in one go, so it is hidden
 * for a background run: that one covers the whole scope, because nobody is waiting on it and
 * splitting it would only make the user come back for the rest.
 */
@Composable
private fun AdvancedSection(
    ui: AiEditUiState,
    plan: RunPlan,
    vm: AiEditViewModel,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExpanderRow(expanded = expanded, onToggle = { expanded = !expanded }) {
            Text(
                stringResource(Res.string.ai_edit_advanced),
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall,
            )
        }
        if (!expanded) return@Column
        HelpText(stringResource(Res.string.ai_edit_advanced_help))
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
                maxDigits = CAP_MAX_DIGITS,
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
                maxDigits = CAP_MAX_DIGITS,
            )
        }
        if (!plan.background) {
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
                maxDigits = CAP_MAX_DIGITS,
            )
        }
    }
}
