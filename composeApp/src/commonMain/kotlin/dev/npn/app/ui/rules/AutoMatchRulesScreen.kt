@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.rules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.SnackbarMessages
import dev.npn.core.app.LibrarySession
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_back
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_delete
import no_papers_needed.composeapp.generated.resources.common_edit
import no_papers_needed.composeapp.generated.resources.common_name
import no_papers_needed.composeapp.generated.resources.common_rules_actions_correspondent
import no_papers_needed.composeapp.generated.resources.common_rules_actions_tags
import no_papers_needed.composeapp.generated.resources.common_rules_actions_type
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
import no_papers_needed.composeapp.generated.resources.common_rules_field_title
import no_papers_needed.composeapp.generated.resources.common_rules_invalid_regex
import no_papers_needed.composeapp.generated.resources.common_rules_join_parts
import no_papers_needed.composeapp.generated.resources.common_rules_match_field
import no_papers_needed.composeapp.generated.resources.common_rules_new_title
import no_papers_needed.composeapp.generated.resources.common_rules_pattern
import no_papers_needed.composeapp.generated.resources.common_rules_priority
import no_papers_needed.composeapp.generated.resources.common_rules_set_correspondent
import no_papers_needed.composeapp.generated.resources.common_rules_set_document_type
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
) {
    val vm: AutoMatchRulesViewModel = viewModel { AutoMatchRulesViewModel(session) }
    val rules by vm.rules.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(vm.messages, snackbar)

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AutoMatchRule?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    AutoMatchRulesScaffold(
        rules = rules,
        snackbar = snackbar,
        onBack = onBack,
        onAdd = {
            editing = null
            showForm = true
        },
        onEdit = {
            editing = it
            showForm = true
        },
        onDelete = { deleteTarget = it.id },
        onToggle = vm::setEnabled,
    )

    if (showForm) {
        RuleFormDialog(
            rule = editing,
            onConfirm = { rule ->
                vm.save(rule)
                showForm = false
                editing = null
            },
            onDismiss = {
                showForm = false
                editing = null
            },
        )
    }

    deleteTarget?.let { id ->
        DeleteRuleDialog(
            onConfirm = {
                vm.delete(id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
}

@Composable
private fun AutoMatchRulesScaffold(
    rules: List<AutoMatchRule>,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (AutoMatchRule) -> Unit,
    onDelete: (AutoMatchRule) -> Unit,
    onToggle: (String, Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.common_rules_title)) },
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
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.common_rules_add))
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (rules.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = stringResource(Res.string.common_rules_empty_title),
                    subtitle = stringResource(Res.string.common_rules_empty_subtitle),
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
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
        onConfirm = onConfirm,
        onDismiss = onDismiss,
    )
}

@Composable
private fun RuleCard(
    rule: AutoMatchRule,
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
                    Text(
                        buildRuleSummary(rule),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = rule.enabled,
                    onCheckedChange = { onToggle(rule.id, it) },
                )
            }
            if (rule.tags.isNotEmpty() || rule.correspondent != null || rule.docType != null) {
                Text(
                    buildActionsLabel(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(onClick = { onEdit(rule) }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(Res.string.common_edit))
                }
                IconButton(onClick = { onDelete(rule) }) {
                    Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.common_delete))
                }
            }
        }
    }
}

@Composable
private fun buildActionsLabel(rule: AutoMatchRule): String {
    val parts =
        buildList {
            if (rule.tags.isNotEmpty()) {
                add(stringResource(Res.string.common_rules_actions_tags, rule.tags.joinToString(", ")))
            }
            rule.correspondent?.let {
                add(stringResource(Res.string.common_rules_actions_correspondent, it))
            }
            rule.docType?.let { add(stringResource(Res.string.common_rules_actions_type, it)) }
        }
    return parts.reduce { summary, part -> stringResource(Res.string.common_rules_join_parts, summary, part) }
}

@Composable
private fun buildRuleSummary(rule: AutoMatchRule): String =
    stringResource(
        Res.string.common_rules_summary,
        fieldLabel(rule.field),
        algorithmLabel(rule.algorithm),
        rule.pattern,
        rule.position,
    )

@Composable
private fun RuleFormDialog(
    rule: AutoMatchRule?,
    onConfirm: (AutoMatchRule) -> Unit,
    onDismiss: () -> Unit,
) {
    var state by remember(rule) { mutableStateOf(rule.toFormState()) }
    val regexValid = state.algorithm != MatchAlgorithm.REGEX || runCatching { Regex(state.pattern) }.isSuccess
    val canSave = state.name.isNotBlank() && state.pattern.isNotBlank() && regexValid

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (rule == null) Res.string.common_rules_new_title else Res.string.common_rules_edit_title,
                ),
            )
        },
        text = {
            RuleFormFields(state, regexValid) { state = it }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        AutoMatchRule(
                            id = rule?.id ?: "",
                            name = state.name.trim(),
                            enabled = rule?.enabled ?: true,
                            field = state.field,
                            algorithm = state.algorithm,
                            pattern = state.pattern.trim(),
                            tags =
                                state.tags
                                    .split(',')
                                    .map { it.trim() }
                                    .filter { it.isNotEmpty() },
                            correspondent = state.correspondent.trim().ifEmpty { null },
                            docType = state.docType.trim().ifEmpty { null },
                            position = state.position.toIntOrNull() ?: 0,
                        ),
                    )
                },
                enabled = canSave,
            ) { Text(stringResource(Res.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

private data class RuleFormState(
    val name: String = "",
    val field: RuleField = RuleField.CONTENT,
    val algorithm: MatchAlgorithm = MatchAlgorithm.ANY_WORD,
    val pattern: String = "",
    val tags: String = "",
    val correspondent: String = "",
    val docType: String = "",
    val position: String = "0",
)

private fun AutoMatchRule?.toFormState(): RuleFormState =
    if (this == null) {
        RuleFormState()
    } else {
        RuleFormState(
            name = name,
            field = field,
            algorithm = algorithm,
            pattern = pattern,
            tags = tags.joinToString(", "),
            correspondent = correspondent ?: "",
            docType = docType ?: "",
            position = position.toString(),
        )
    }

@Composable
private fun RuleFormFields(
    state: RuleFormState,
    regexValid: Boolean,
    onChange: (RuleFormState) -> Unit,
) {
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
        FieldDropdown(state.field) { onChange(state.copy(field = it)) }
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
        FormTextField(state.position, stringResource(Res.string.common_rules_priority)) {
            onChange(state.copy(position = it.filter(Char::isDigit)))
        }
        FormTextField(state.tags, stringResource(Res.string.common_rules_tags_to_add)) {
            onChange(state.copy(tags = it))
        }
        FormTextField(state.correspondent, stringResource(Res.string.common_rules_set_correspondent)) {
            onChange(state.copy(correspondent = it))
        }
        FormTextField(state.docType, stringResource(Res.string.common_rules_set_document_type)) {
            onChange(state.copy(docType = it))
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
    onSelect: (RuleField) -> Unit,
) {
    SimpleDropdown(
        label = stringResource(Res.string.common_rules_match_field),
        current = fieldLabel(current),
        options = RuleField.entries,
        labelOf = ::fieldLabel,
        onSelect = onSelect,
    )
}

@Composable
private fun AlgorithmDropdown(
    current: MatchAlgorithm,
    onSelect: (MatchAlgorithm) -> Unit,
) {
    SimpleDropdown(
        label = stringResource(Res.string.common_rules_algorithm),
        current = algorithmLabel(current),
        options = MatchAlgorithm.entries,
        labelOf = ::algorithmLabel,
        onSelect = onSelect,
    )
}

@Composable
private fun <T> SimpleDropdown(
    label: String,
    current: String,
    options: List<T>,
    labelOf: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(label, style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { expanded = true }) {
            Text(current)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(labelOf(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun fieldLabel(field: RuleField): String = stringResource(field.labelResource)

private val RuleField.labelResource: StringResource
    get() =
        when (this) {
            RuleField.CONTENT -> Res.string.common_rules_field_content
            RuleField.TITLE -> Res.string.common_rules_field_title
            RuleField.FILE_NAME -> Res.string.common_rules_field_file_name
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
