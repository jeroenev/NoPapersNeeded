package dev.npn.app.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Rule
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.LoadingIndicator
import dev.npn.app.ui.common.NumericField
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.rememberSnackbarMessages
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.FieldDef
import dev.npn.core.model.labelFor
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import dev.npn.core.rules.RuleMatcher
import dev.npn.core.util.NpnJson
import kotlinx.serialization.Serializable
import no_papers_needed.composeapp.generated.resources.Res
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
import no_papers_needed.composeapp.generated.resources.common_rules_priority
import no_papers_needed.composeapp.generated.resources.common_rules_set_field
import no_papers_needed.composeapp.generated.resources.common_rules_summary
import no_papers_needed.composeapp.generated.resources.common_rules_tags_to_add
import no_papers_needed.composeapp.generated.resources.common_rules_title
import no_papers_needed.composeapp.generated.resources.common_save
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

@Composable
fun AutoMatchRulesScreen(
    session: LibrarySession,
    onBack: () -> Unit,
    vm: AutoMatchRulesViewModel = viewModel { AutoMatchRulesViewModel(session) },
) {
    val rules by vm.rules.collectAsStateWithLifecycle()
    val fields by vm.fields.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarMessages(vm.messages)

    // Saveable, form fields included, so an open dialog is back after the screen is recreated.
    var dialog by rememberSaveable(stateSaver = RuleDialog.Saver) { mutableStateOf(null) }

    AutoMatchRulesScaffold(
        rules = rules,
        fields = fields,
        snackbar = snackbar,
        onBack = onBack,
        onAdd = { dialog = RuleDialog.Form(RuleFormState()) },
        onEdit = { dialog = RuleDialog.Form(RuleFormState.of(it)) },
        onDelete = { dialog = RuleDialog.Delete(it.id) },
        onToggle = vm::setEnabled,
    )

    when (val open = dialog) {
        is RuleDialog.Form -> {
            RuleFormDialog(
                state = open.form,
                fields = fields,
                onChange = { dialog = RuleDialog.Form(it) },
                onConfirm = {
                    vm.save(open.form.toRule())
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }

        is RuleDialog.Delete -> {
            DeleteRuleDialog(
                onConfirm = {
                    vm.delete(open.id)
                    dialog = null
                },
                onDismiss = { dialog = null },
            )
        }

        null -> {}
    }
}

/** The one dialog the rules screen has open, or none; saved as JSON. */
@Serializable
private sealed interface RuleDialog {
    @Serializable
    data class Form(
        val form: RuleFormState,
    ) : RuleDialog

    @Serializable
    data class Delete(
        val id: String,
    ) : RuleDialog

    companion object {
        val Saver: Saver<RuleDialog?, String> =
            Saver(
                save = { dialog -> dialog?.let { NpnJson.Persisted.encodeToString(serializer(), it) } },
                restore = { NpnJson.Persisted.decodeFromString(serializer(), it) },
            )
    }
}

@Composable
private fun AutoMatchRulesScaffold(
    /** Null until the first load, which shows a spinner rather than the empty state. */
    rules: List<AutoMatchRule>?,
    fields: List<FieldDef>,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (AutoMatchRule) -> Unit,
    onDelete: (AutoMatchRule) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    Scaffold(
        topBar = { BackTopBar(title = stringResource(Res.string.common_rules_title), onBack = onBack) },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.common_rules_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (rules == null) {
            LoadingIndicator(Modifier.padding(padding))
        } else if (rules.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Filled.Rule,
                title = stringResource(Res.string.common_rules_empty_title),
                modifier = Modifier.padding(padding),
                subtitle = stringResource(Res.string.common_rules_empty_subtitle),
            )
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        fields = fields,
                        onEdit = onEdit,
                        onDelete = onDelete,
                        onToggle = onToggle,
                    )
                    HorizontalDivider()
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
    onEdit: (AutoMatchRule) -> Unit,
    onDelete: (AutoMatchRule) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    Card(
        onClick = { onEdit(rule) },
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
                    onCheckedChange = { onToggle(rule.id, it) },
                )
            }
            if (rule.tags.isNotEmpty() || rule.sets.isNotEmpty()) {
                HelpText(buildActionsLabel(rule, fields))
            }
            // The card itself opens the rule for editing; only deleting needs a button of its own.
            TooltipIconButton(Icons.Filled.Delete, stringResource(Res.string.common_delete), { onDelete(rule) })
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
        rule.position,
    )

@Composable
private fun RuleFormDialog(
    state: RuleFormState,
    fields: List<FieldDef>,
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
        confirmEnabled = state.canSave,
    ) {
        RuleFormFields(state, fields, onChange)
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
    /** Field key -> value the rule sets; blank values are dropped on save. */
    val sets: Map<String, String> = emptyMap(),
    val position: Int = 0,
) {
    val isNew: Boolean get() = id.isEmpty()

    /** False only for a regex pattern that does not compile, as the matcher will read it. */
    val patternValid: Boolean
        get() = RuleMatcher.isValidPattern(algorithm, pattern)

    val canSave: Boolean get() = name.isNotBlank() && pattern.isNotBlank() && patternValid

    fun toRule(): AutoMatchRule =
        AutoMatchRule(
            id = id,
            name = name.trim(),
            enabled = enabled,
            field = field,
            algorithm = algorithm,
            pattern = pattern.trim(),
            tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
            sets = sets.mapValues { it.value.trim() }.filterValues { it.isNotEmpty() },
            position = position,
        )

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
        NumericField(
            value = state.position,
            onValueChange = { onChange(state.copy(position = it)) },
            range = RULE_PRIORITIES,
            label = stringResource(Res.string.common_rules_priority),
        )
        FormTextField(state.tags, stringResource(Res.string.common_rules_tags_to_add)) {
            onChange(state.copy(tags = it))
        }
        // One "set" row per single-line registry field, in registry order. Prose fields are left
        // out: a rule stamping the same paragraph onto every match is not a use case worth a row.
        for (def in fields.filter { it.type.singleLine }) {
            FormTextField(state.sets[def.key].orEmpty(), stringResource(Res.string.common_rules_set_field, def.label)) {
                onChange(state.copy(sets = state.sets + (def.key to it)))
            }
        }
    }
}

/** The priorities a rule can have; rules run in priority order, lowest first. */
private val RULE_PRIORITIES = 0..9999

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
