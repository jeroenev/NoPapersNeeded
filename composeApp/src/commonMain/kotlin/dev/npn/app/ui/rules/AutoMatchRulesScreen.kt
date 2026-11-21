package dev.npn.app.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.FieldEditor
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.LoadingIndicator
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.rememberSnackbarMessages
import dev.npn.app.ui.common.resolve
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldValues
import dev.npn.core.model.labelFor
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import dev.npn.core.rules.RuleMatcher
import kotlinx.serialization.Serializable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.automation_algorithm_all_words_help
import no_papers_needed.composeapp.generated.resources.automation_algorithm_any_word_help
import no_papers_needed.composeapp.generated.resources.automation_algorithm_exact_help
import no_papers_needed.composeapp.generated.resources.automation_algorithm_fuzzy_help
import no_papers_needed.composeapp.generated.resources.automation_algorithm_regex_help
import no_papers_needed.composeapp.generated.resources.automation_rule_preview_checking
import no_papers_needed.composeapp.generated.resources.automation_rule_preview_fills_in
import no_papers_needed.composeapp.generated.resources.automation_rule_preview_hint
import no_papers_needed.composeapp.generated.resources.automation_rules_order_help
import no_papers_needed.composeapp.generated.resources.common_delete
import no_papers_needed.composeapp.generated.resources.common_name
import no_papers_needed.composeapp.generated.resources.common_rules_actions_set
import no_papers_needed.composeapp.generated.resources.common_rules_actions_tags
import no_papers_needed.composeapp.generated.resources.common_rules_add
import no_papers_needed.composeapp.generated.resources.common_rules_algorithm
import no_papers_needed.composeapp.generated.resources.common_rules_algorithm_all_words
import no_papers_needed.composeapp.generated.resources.common_rules_algorithm_any_word
import no_papers_needed.composeapp.generated.resources.common_rules_algorithm_exact
import no_papers_needed.composeapp.generated.resources.common_rules_algorithm_fuzzy
import no_papers_needed.composeapp.generated.resources.common_rules_algorithm_regex
import no_papers_needed.composeapp.generated.resources.common_rules_delete_message
import no_papers_needed.composeapp.generated.resources.common_rules_delete_title
import no_papers_needed.composeapp.generated.resources.common_rules_edit_title
import no_papers_needed.composeapp.generated.resources.common_rules_empty_subtitle
import no_papers_needed.composeapp.generated.resources.common_rules_empty_title
import no_papers_needed.composeapp.generated.resources.common_rules_field_content
import no_papers_needed.composeapp.generated.resources.common_rules_field_file_name
import no_papers_needed.composeapp.generated.resources.common_rules_invalid_regex
import no_papers_needed.composeapp.generated.resources.common_rules_join_parts
import no_papers_needed.composeapp.generated.resources.common_rules_match_field
import no_papers_needed.composeapp.generated.resources.common_rules_new_title
import no_papers_needed.composeapp.generated.resources.common_rules_pattern
import no_papers_needed.composeapp.generated.resources.common_rules_set_field
import no_papers_needed.composeapp.generated.resources.common_rules_summary
import no_papers_needed.composeapp.generated.resources.common_rules_tags_to_add
import no_papers_needed.composeapp.generated.resources.common_rules_title
import no_papers_needed.composeapp.generated.resources.common_save
import no_papers_needed.composeapp.generated.resources.common_settings_field_move_down
import no_papers_needed.composeapp.generated.resources.common_settings_field_move_up
import no_papers_needed.composeapp.generated.resources.common_settings_syncs_across_devices
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun AutoMatchRulesScreen(
    session: LibrarySession,
    onBack: () -> Unit,
    vm: AutoMatchRulesViewModel = viewModel { AutoMatchRulesViewModel(session, createSavedStateHandle()) },
) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val fields by vm.fields.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarMessages(vm.messages)

    // The viewmodel's, form fields included, so an open dialog is back after the screen is recreated.
    val dialog by vm.dialog.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()

    val actions =
        remember(vm) {
            object : RuleListActions {
                override fun add() = vm.openDialog(RuleDialog.Form(RuleFormState()))

                override fun edit(rule: AutoMatchRule) = vm.openDialog(RuleDialog.Form(RuleFormState.of(rule)))

                override fun delete(rule: AutoMatchRule) = vm.openDialog(RuleDialog.Delete(rule.id))

                override fun setEnabled(
                    id: String,
                    enabled: Boolean,
                ) = vm.setEnabled(id, enabled)

                override fun move(
                    id: String,
                    delta: Int,
                ) = vm.move(id, delta)
            }
        }

    AutoMatchRulesScaffold(
        rules = rules,
        fields = fields,
        snackbar = snackbar,
        onBack = onBack,
        actions = actions,
    )

    when (val open = dialog) {
        is RuleDialog.Form -> {
            RuleFormDialog(
                state = open.form,
                fields = fields,
                preview = preview,
                onChange = { vm.openDialog(RuleDialog.Form(it)) },
                onConfirm = {
                    vm.save(open.form.toRule())
                    vm.openDialog(null)
                },
                onDismiss = { vm.openDialog(null) },
            )
        }

        is RuleDialog.Delete -> {
            DeleteRuleDialog(
                onConfirm = {
                    vm.delete(open.id)
                    vm.openDialog(null)
                },
                onDismiss = { vm.openDialog(null) },
            )
        }

        null -> {}
    }
}

/** What the rule list can do to its rules. */
internal interface RuleListActions {
    fun add()

    fun edit(rule: AutoMatchRule)

    fun delete(rule: AutoMatchRule)

    fun setEnabled(
        id: String,
        enabled: Boolean,
    )

    /** Move rule [id] [delta] places in the run order: -1 up, +1 down. */
    fun move(
        id: String,
        delta: Int,
    )
}

@Composable
private fun AutoMatchRulesScaffold(
    /** Null until the first load, which shows a spinner rather than the empty state. In run order. */
    rules: List<AutoMatchRule>?,
    fields: List<FieldDef>,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    actions: RuleListActions,
) {
    Scaffold(
        topBar = { BackTopBar(title = stringResource(Res.string.common_rules_title), onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = actions::add) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.common_rules_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                HelpText(stringResource(Res.string.automation_rules_order_help))
                HelpText(stringResource(Res.string.common_settings_syncs_across_devices))
            }
            if (rules == null) {
                LoadingIndicator(Modifier.weight(1f))
            } else if (rules.isEmpty()) {
                EmptyState(
                    icon = Icons.AutoMirrored.Filled.Rule,
                    title = stringResource(Res.string.common_rules_empty_title),
                    modifier = Modifier.weight(1f),
                    subtitle = stringResource(Res.string.common_rules_empty_subtitle),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    itemsIndexed(rules, key = { _, rule -> rule.id }) { index, rule ->
                        RuleCard(
                            rule = rule,
                            fields = fields,
                            canMoveUp = index > 0,
                            canMoveDown = index < rules.lastIndex,
                            actions = actions,
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun DeleteRuleDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title = stringResource(Res.string.common_rules_delete_title),
        message = stringResource(Res.string.common_rules_delete_message),
        confirmLabel = stringResource(Res.string.common_delete),
        destructive = true,
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun RuleCard(
    rule: AutoMatchRule,
    fields: List<FieldDef>,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    actions: RuleListActions,
) {
    Card(
        onClick = { actions.edit(rule) },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleMedium)
                    HelpText(buildRuleSummary(rule, fields))
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { actions.setEnabled(rule.id, it) },
                )
            }
            if (rule.tags.isNotEmpty() || rule.sets.isNotEmpty()) {
                HelpText(buildActionsLabel(rule, fields))
            }
            // The card itself opens the rule for editing; moving and deleting need buttons of their own.
            Row(verticalAlignment = Alignment.CenterVertically) {
                TooltipIconButton(
                    Icons.Filled.KeyboardArrowUp,
                    stringResource(Res.string.common_settings_field_move_up),
                    onClick = { actions.move(rule.id, -1) },
                    enabled = canMoveUp,
                )
                TooltipIconButton(
                    Icons.Filled.KeyboardArrowDown,
                    stringResource(Res.string.common_settings_field_move_down),
                    onClick = { actions.move(rule.id, 1) },
                    enabled = canMoveDown,
                )
                TooltipIconButton(
                    Icons.Filled.Delete,
                    stringResource(Res.string.common_delete),
                    { actions.delete(rule) },
                )
            }
        }
    }
}

@Composable
private fun buildActionsLabel(
    rule: AutoMatchRule,
    fields: List<FieldDef>,
): String {
    val parts =
        buildList {
            if (rule.tags.isNotEmpty()) {
                add(stringResource(Res.string.common_rules_actions_tags, rule.tags.joinToString(", ")))
            }
            for ((key, value) in rule.sets) {
                add(stringResource(Res.string.common_rules_actions_set, fields.labelFor(key), value))
            }
        }
    return parts.reduce { summary, part -> stringResource(Res.string.common_rules_join_parts, summary, part) }
}

@Composable
private fun buildRuleSummary(
    rule: AutoMatchRule,
    fields: List<FieldDef>,
): String =
    stringResource(
        Res.string.common_rules_summary,
        fieldLabel(rule.field, fields),
        algorithmLabel(rule.algorithm),
        rule.pattern,
    )

@Composable
private fun RuleFormDialog(
    state: RuleFormState,
    fields: List<FieldDef>,
    preview: RulePreview?,
    onChange: (RuleFormState) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    ConfirmDialog(
        title =
            stringResource(
                if (state.isNew) Res.string.common_rules_new_title else Res.string.common_rules_edit_title,
            ),
        onConfirm = onConfirm,
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.common_save),
        confirmEnabled = state.canSave && state.setsFit(fields),
    ) {
        RuleFormFields(state, fields, preview, onChange)
    }
}

/**
 * The rule form as the user edits it: text as typed, turned into a rule only on save ([toRule]).
 * [id] is empty for a new rule.
 */
@Serializable
internal data class RuleFormState(
    val id: String = "",
    val enabled: Boolean = true,
    val name: String = "",
    val field: RuleField = RuleField.Content,
    val algorithm: MatchAlgorithm = MatchAlgorithm.ANY_WORD,
    val pattern: String = "",
    val tags: String = "",
    /**
     * Field key -> value the rule sets, canonical as the field's editor reads it ([withSet]), or as
     * typed for a key in [refused]; blank values are dropped on save.
     */
    val sets: Map<String, String> = emptyMap(),
    val position: Int = 0,
    /** Keys whose typed value does not fit the field's type, so the rule cannot be saved yet. */
    val refused: Set<String> = emptySet(),
) {
    val isNew: Boolean get() = id.isEmpty()

    /** False only for a regex pattern that does not compile, as the matcher will read it. */
    val patternValid: Boolean
        get() = RuleMatcher.isValidPattern(algorithm, pattern)

    val canSave: Boolean get() = name.isNotBlank() && pattern.isNotBlank() && patternValid

    /**
     * Whether every value the rule sets fits its field's type among [fields]: one typed that does not
     * ([refused]), or one an older rule stored off-type, would be skipped by the rule every time, so
     * the rule is not saved with it.
     */
    fun setsFit(fields: List<FieldDef>): Boolean =
        refused.isEmpty() &&
            fields.none { def ->
                sets[def.key]?.let { FieldValues.canonicalize(def.type, it) is FieldParse.Invalid } ==
                    true
            }

    /** This form with what the editor of field [key] made of the value typed for it. */
    fun withSet(
        key: String,
        parse: FieldParse,
    ): RuleFormState =
        when (parse) {
            is FieldParse.Ok -> {
                copy(sets = parse.canonical?.let { sets + (key to it) } ?: (sets - key), refused = refused - key)
            }

            is FieldParse.Invalid -> {
                copy(sets = sets + (key to parse.raw), refused = refused + key)
            }
        }

    fun toRule(): AutoMatchRule =
        AutoMatchRule(
            id = id,
            name = name,
            enabled = enabled,
            field = field,
            algorithm = algorithm,
            pattern = pattern,
            tags = tags.split(','),
            sets = sets,
            position = position,
        ).normalized()

    companion object {
        fun of(rule: AutoMatchRule): RuleFormState =
            RuleFormState(
                id = rule.id,
                enabled = rule.enabled,
                name = rule.name,
                field = rule.field,
                algorithm = rule.algorithm,
                pattern = rule.pattern,
                tags = rule.tags.joinToString(", "),
                sets = rule.sets,
                position = rule.position,
            )
    }
}

@Composable
private fun RuleFormFields(
    state: RuleFormState,
    fields: List<FieldDef>,
    preview: RulePreview?,
    onChange: (RuleFormState) -> Unit,
) {
    val regexValid = state.patternValid
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            state.name,
            { onChange(state.copy(name = it)) },
            label = { Text(stringResource(Res.string.common_name)) },
            singleLine = true,
        )
        FieldDropdown(state.field, fields) { onChange(state.copy(field = it)) }
        AlgorithmDropdown(state.algorithm) { onChange(state.copy(algorithm = it)) }
        HelpText(stringResource(state.algorithm.helpResource))
        OutlinedTextField(
            state.pattern,
            { onChange(state.copy(pattern = it)) },
            label = { Text(stringResource(Res.string.common_rules_pattern)) },
            supportingText =
                if (regexValid) {
                    null
                } else {
                    (
                        {
                            Text(stringResource(Res.string.common_rules_invalid_regex))
                        }
                    )
                },
            isError = !regexValid,
            singleLine = true,
        )
        RulePreviewText(preview)
        FormTextField(state.tags, stringResource(Res.string.common_rules_tags_to_add)) {
            onChange(state.copy(tags = it))
        }
        // One "set" row per single-line registry field, in registry order. Prose fields are left
        // out: a rule stamping the same paragraph onto every match is not a use case worth a row.
        // Each is the field's own editor (a date picker, a number as the locale writes it), so the
        // rule stores the canonical value the applier will write, and a value the type refuses
        // shows as an error and blocks the save instead of being skipped at every match.
        for (def in fields.filter { it.type.singleLine }) {
            FieldEditor(
                def = def.copy(label = stringResource(Res.string.common_rules_set_field, def.label)),
                value = state.sets[def.key],
                onCommit = { onChange(state.withSet(def.key, it)) },
                refused = def.key in state.refused,
            )
        }
    }
}

/**
 * What the rule as typed matches in the library now: how many documents, the first few by name, and
 * that saving only fills in what they lack. A hint while there is no pattern to match yet.
 */
@Composable
private fun RulePreviewText(preview: RulePreview?) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        when (preview) {
            null -> {
                HelpText(stringResource(Res.string.automation_rule_preview_hint))
            }

            RulePreview.Checking -> {
                HelpText(stringResource(Res.string.automation_rule_preview_checking))
            }

            is RulePreview.Matches -> {
                Text(preview.summary.resolve(), style = MaterialTheme.typography.bodyMedium)
                for (title in preview.titles) {
                    HelpText(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                preview.more?.let { HelpText(it.resolve()) }
                if (preview.count > 0) HelpText(stringResource(Res.string.automation_rule_preview_fills_in))
            }
        }
    }
}

@Composable
private fun FormTextField(
    value: String,
    label: String,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true)
}

@Composable
private fun FieldDropdown(
    current: RuleField,
    fields: List<FieldDef>,
    onSelect: (RuleField) -> Unit,
) {
    Dropdown(
        selected = fieldLabel(current, fields),
        options = listOf(RuleField.Content, RuleField.FileName) + fields.map { RuleField.Field(it.key) },
        optionLabel = { fieldLabel(it, fields) },
        onSelect = onSelect,
        label = stringResource(Res.string.common_rules_match_field),
    )
}

@Composable
private fun AlgorithmDropdown(
    current: MatchAlgorithm,
    onSelect: (MatchAlgorithm) -> Unit,
) {
    Dropdown(
        selected = algorithmLabel(current),
        options = MatchAlgorithm.entries,
        optionLabel = ::algorithmLabel,
        onSelect = onSelect,
        label = stringResource(Res.string.common_rules_algorithm),
    )
}

@Composable
private fun fieldLabel(
    field: RuleField,
    fields: List<FieldDef>,
): String =
    when (field) {
        RuleField.Content -> stringResource(Res.string.common_rules_field_content)
        RuleField.FileName -> stringResource(Res.string.common_rules_field_file_name)
        is RuleField.Field -> fields.labelFor(field.key)
    }

@Composable
private fun algorithmLabel(algorithm: MatchAlgorithm): String = stringResource(algorithm.labelResource)

private val MatchAlgorithm.labelResource: StringResource
    get() =
        when (this) {
            MatchAlgorithm.EXACT -> Res.string.common_rules_algorithm_exact
            MatchAlgorithm.ANY_WORD -> Res.string.common_rules_algorithm_any_word
            MatchAlgorithm.ALL_WORDS -> Res.string.common_rules_algorithm_all_words
            MatchAlgorithm.REGEX -> Res.string.common_rules_algorithm_regex
            MatchAlgorithm.FUZZY -> Res.string.common_rules_algorithm_fuzzy
        }

/** What [MatchAlgorithm] does, in plain words, shown under the algorithm picker. */
private val MatchAlgorithm.helpResource: StringResource
    get() =
        when (this) {
            MatchAlgorithm.EXACT -> Res.string.automation_algorithm_exact_help
            MatchAlgorithm.ANY_WORD -> Res.string.automation_algorithm_any_word_help
            MatchAlgorithm.ALL_WORDS -> Res.string.automation_algorithm_all_words_help
            MatchAlgorithm.REGEX -> Res.string.automation_algorithm_regex_help
            MatchAlgorithm.FUZZY -> Res.string.automation_algorithm_fuzzy_help
        }
