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
import androidx.compose.runtime.LaunchedEffect
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
import dev.npn.core.app.LibrarySession
import dev.npn.core.rules.AutoMatchRule
import dev.npn.core.rules.MatchAlgorithm
import dev.npn.core.rules.RuleField

@Composable
fun AutoMatchRulesScreen(
    session: LibrarySession,
    onBack: () -> Unit,
) {
    val vm: AutoMatchRulesViewModel = viewModel { AutoMatchRulesViewModel(session) }
    val rules by vm.rules.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm, snackbar) { vm.messages.collect { snackbar.showSnackbar(it) } }

    var showForm by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AutoMatchRule?>(null) }
    var deleteTarget by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Auto-matching rules") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = {
                editing = null
                showForm = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Add rule")
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (rules.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.Add,
                    title = "No rules yet",
                    subtitle = "Create a rule to tag documents automatically based on their text.",
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(rules, key = { it.id }) { rule ->
                    RuleCard(
                        rule = rule,
                        onEdit = { editing = it; showForm = true },
                        onDelete = { deleteTarget = it.id },
                        onToggle = { id, enabled -> vm.setEnabled(id, enabled) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

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
        ConfirmDialog(
            title = "Delete rule",
            message = "Remove this rule? It will not undo tags already applied.",
            confirmLabel = "Delete",
            onConfirm = {
                vm.delete(id)
                deleteTarget = null
            },
            onDismiss = { deleteTarget = null },
        )
    }
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
                    Icon(Icons.Filled.Edit, contentDescription = "Edit")
                }
                IconButton(onClick = { onDelete(rule) }) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete")
                }
            }
        }
    }
}

private fun buildActionsLabel(rule: AutoMatchRule): String = buildString {
    if (rule.tags.isNotEmpty()) append("Tags: ${rule.tags.joinToString(", ")}")
    if (rule.correspondent != null) {
        if (isNotEmpty()) append("  ·  ")
        append("Correspondent: ${rule.correspondent}")
    }
    if (rule.docType != null) {
        if (isNotEmpty()) append("  ·  ")
        append("Type: ${rule.docType}")
    }
}

private fun buildRuleSummary(rule: AutoMatchRule): String =
    "${fieldLabel(rule.field)} · ${algorithmLabel(rule.algorithm)} · " +
        "${rule.pattern} · priority ${rule.position}"

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
        title = { Text(if (rule == null) "New rule" else "Edit rule") },
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
                            tags = state.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
                            correspondent = state.correspondent.trim().ifEmpty { null },
                            docType = state.docType.trim().ifEmpty { null },
                            position = state.position.toIntOrNull() ?: 0,
                        ),
                    )
                },
                enabled = canSave,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
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

private fun AutoMatchRule?.toFormState(): RuleFormState = if (this == null) {
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
private fun RuleFormFields(state: RuleFormState, regexValid: Boolean, onChange: (RuleFormState) -> Unit) {
    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(state.name, { onChange(state.copy(name = it)) }, label = { Text("Name") }, singleLine = true)
        FieldDropdown(state.field) { onChange(state.copy(field = it)) }
        AlgorithmDropdown(state.algorithm) { onChange(state.copy(algorithm = it)) }
        OutlinedTextField(
            state.pattern,
            { onChange(state.copy(pattern = it)) },
            label = { Text("Pattern") },
            supportingText = if (regexValid) null else ({ Text("Invalid regular expression") }),
            isError = !regexValid,
            singleLine = true,
        )
        FormTextField(state.position, "Priority") {
            onChange(state.copy(position = it.filter(Char::isDigit)))
        }
        FormTextField(state.tags, "Tags to add (comma separated)") { onChange(state.copy(tags = it)) }
        FormTextField(state.correspondent, "Set correspondent (optional)") {
            onChange(state.copy(correspondent = it))
        }
        FormTextField(state.docType, "Set document type (optional)") { onChange(state.copy(docType = it)) }
    }
}

@Composable
private fun FormTextField(value: String, label: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true)
}

@Composable
private fun FieldDropdown(current: RuleField, onSelect: (RuleField) -> Unit) {
    SimpleDropdown(
        label = "Match field",
        current = fieldLabel(current),
        options = RuleField.entries,
        labelOf = ::fieldLabel,
        onSelect = onSelect,
    )
}

@Composable
private fun AlgorithmDropdown(current: MatchAlgorithm, onSelect: (MatchAlgorithm) -> Unit) {
    SimpleDropdown(
        label = "Algorithm",
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
    labelOf: (T) -> String,
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

private fun fieldLabel(field: RuleField): String = when (field) {
    RuleField.CONTENT -> "Extracted text"
    RuleField.TITLE -> "Title"
    RuleField.FILE_NAME -> "File name"
}

private fun algorithmLabel(algorithm: MatchAlgorithm): String = when (algorithm) {
    MatchAlgorithm.EXACT -> "Exact"
    MatchAlgorithm.ANY_WORD -> "Any word"
    MatchAlgorithm.ALL_WORDS -> "All words"
    MatchAlgorithm.REGEX -> "Regex"
    MatchAlgorithm.FUZZY -> "Fuzzy"
}
