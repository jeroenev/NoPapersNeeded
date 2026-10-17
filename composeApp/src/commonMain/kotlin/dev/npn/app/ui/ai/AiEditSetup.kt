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
import dev.npn.app.ui.common.AddFieldMenu
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.ExpanderRow
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.NumericField
import dev.npn.app.ui.common.RadioRow
import dev.npn.app.ui.common.SwitchRow
import dev.npn.core.ai.AiTagging
import dev.npn.core.app.DocContentFacade
import dev.npn.core.app.aiedit.EditScope
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
    preview: ScopePreview,
    plan: RunPlan,
    blocker: StartBlocker?,
    setup: AiEditSetupActions,
    content: DocContentFacade,
) {
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
                        TaskChooser(onPick = setup::setTask)
                    } else {
                        ChosenTask(task, onChange = setup::clearTask)
                        SetupForm(ui, preview, plan, setup, content, wide)
                    }
                }
            }
        }
        RunBar(ui = ui, plan = plan, blocker = blocker, onStart = setup::start)
    }
}

@Composable
private fun SetupForm(
    ui: AiEditUiState,
    preview: ScopePreview,
    plan: RunPlan,
    setup: AiEditSetupActions,
    content: DocContentFacade,
    wide: Boolean,
) {
    if (wide) {
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            SetupColumn(Modifier.weight(1f)) {
                ScopeSection(ui, preview, setup, content)
                FieldSection(ui, setup)
            }
            SetupColumn(Modifier.weight(1f)) {
                TagSection(ui, setup)
                PromptField(ui.customPrompt, setup::setCustomPrompt)
                RunSection(ui, plan, setup)
            }
        }
    } else {
        ScopeSection(ui, preview, setup, content)
        TagSection(ui, setup)
        FieldSection(ui, setup)
        PromptField(ui.customPrompt, setup::setCustomPrompt)
        RunSection(ui, plan, setup)
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
    setup: AiEditSetupActions,
    content: DocContentFacade,
) {
    SetupSection(stringResource(Res.string.ai_edit_scope)) {
        EditScope.entries.filter { ui.scopeAvailable(it) || it == ui.scope }.forEach { scope ->
            RadioRow(scopeLabel(scope, ui), selected = ui.scope == scope, onSelect = { setup.setScope(scope) })
            if (scope == EditScope.SAVED_VIEW && ui.scope == EditScope.SAVED_VIEW) {
                SavedViewPicker(ui, setup::setSavedView)
            }
        }
        val runFields = ui.enabledFieldKeys().mapNotNull { key -> ui.fields.firstOrNull { it.key == key } }
        BatchPreview(preview, ui.batchSize, runFields, content)
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
    onSelect: (id: String) -> Unit,
) {
    val current = ui.savedViews.firstOrNull { it.id == ui.savedViewId }
    Dropdown(
        selected = current?.name ?: stringResource(Res.string.ai_edit_saved_view_choose),
        options = ui.savedViews,
        optionLabel = { it.name },
        onSelect = { onSelect(it.id) },
        modifier = Modifier.padding(start = 48.dp),
    )
}

/** Tag additions and tag removals; the caps they belong to live under Advanced. */
@Composable
private fun TagSection(
    ui: AiEditUiState,
    setup: AiEditSetupActions,
) {
    SetupSection(stringResource(Res.string.ai_edit_tags)) {
        SwitchRow(
            label = stringResource(Res.string.ai_edit_suggest_tags),
            help = stringResource(Res.string.ai_edit_suggest_tags_help),
            checked = ui.includeTags,
            onCheckedChange = setup::setIncludeTags,
        )
        if (ui.includeTags) {
            SwitchRow(
                label = stringResource(Res.string.ai_edit_allow_new_tags),
                help = stringResource(Res.string.ai_edit_allow_new_tags_help),
                checked = ui.allowNewTags,
                onCheckedChange = setup::setAllowNewTags,
            )
        }
        SwitchRow(
            label = stringResource(Res.string.ai_edit_suggest_tag_removals),
            help = stringResource(Res.string.ai_edit_suggest_tag_removals_help),
            checked = ui.includeTagRemovals,
            onCheckedChange = setup::setIncludeTagRemovals,
        )
    }
}

@Composable
private fun FieldSection(
    ui: AiEditUiState,
    setup: AiEditSetupActions,
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
        for (key in ui.fieldOptions.keys.inRegistryOrder(ui.fields)) {
            FieldOption(
                label = labels[key] ?: key,
                option = ui.fieldOptions.getValue(key),
                onEnabledChange = { setup.setFieldEnabled(key, it) },
                onModeChange = { setup.setFieldMode(key, it) },
            )
        }
        // "Add a field to this run": every registered field the run does not list yet.
        val available = ui.fields.filter { it.key !in ui.fieldOptions }
        if (available.isNotEmpty()) {
            AddFieldMenu(
                label = stringResource(Res.string.ai_edit_add_field),
                fields = available,
                onPick = { setup.addField(it.key) },
                outlined = false,
            )
        }
    }
}

@Composable
private fun PromptField(
    prompt: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = prompt,
        onValueChange = onChange,
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
    setup: AiEditSetupActions,
) {
    SetupSection(stringResource(Res.string.ai_edit_run_where)) {
        RunPlacementOption(
            label = stringResource(Res.string.ai_edit_run_now),
            help = stringResource(Res.string.ai_edit_run_now_help),
            selected = !plan.background,
            recommended = plan.loaded && ui.backgroundOverride == null && !plan.background,
            onSelect = { setup.setBackground(false) },
        )
        RunPlacementOption(
            label = stringResource(Res.string.ai_edit_background),
            help = stringResource(Res.string.ai_edit_background_help),
            selected = plan.background,
            recommended = plan.loaded && ui.backgroundOverride == null && plan.background,
            onSelect = { setup.setBackground(true) },
        )
        AdvancedSection(ui, plan, setup)
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
    RadioRow(label, selected, onSelect) {
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
    setup: AiEditSetupActions,
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
                onValueChange = setup::setMaxTags,
                range = AiTagging.RANGE,
                label = stringResource(Res.string.ai_edit_tags_per_document),
                supportingText =
                    stringResource(
                        Res.string.ai_edit_range,
                        AiTagging.MIN_TAGS_PER_DOC,
                        AiTagging.MAX_TAGS_PER_DOC,
                    ),
            )
        }
        // The cap only means anything while brand-new tags are allowed at all.
        if (ui.includeTags && ui.allowNewTags) {
            NumericField(
                value = ui.maxNewTags,
                onValueChange = setup::setMaxNewTags,
                range = AiTagging.NEW_TAGS_RANGE,
                label = stringResource(Res.string.ai_edit_new_tags_per_document),
                supportingText =
                    stringResource(
                        Res.string.ai_edit_new_tags_range,
                        AiTagging.MIN_NEW_TAGS_PER_DOC,
                        AiTagging.MAX_NEW_TAGS_PER_DOC,
                        AiTagging.MIN_NEW_TAGS_PER_DOC,
                    ),
            )
        }
        if (!plan.background) {
            NumericField(
                value = ui.batchSize,
                onValueChange = setup::setBatchSize,
                range = AiTagging.BATCH_RANGE,
                label = stringResource(Res.string.ai_edit_documents_per_batch),
                supportingText =
                    stringResource(
                        Res.string.ai_edit_range,
                        AiTagging.MIN_BATCH_DOCS,
                        AiTagging.MAX_BATCH_DOCS,
                    ),
            )
        }
    }
}
