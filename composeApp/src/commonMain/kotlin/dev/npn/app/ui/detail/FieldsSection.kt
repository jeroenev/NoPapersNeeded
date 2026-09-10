@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.FieldEditor
import dev.npn.app.ui.common.fieldTypeLabel
import dev.npn.core.app.Doc
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_save
import no_papers_needed.composeapp.generated.resources.detail_add_field
import no_papers_needed.composeapp.generated.resources.detail_add_to_fields
import no_papers_needed.composeapp.generated.resources.detail_field_hint
import no_papers_needed.composeapp.generated.resources.detail_field_key
import no_papers_needed.composeapp.generated.resources.detail_field_key_taken
import no_papers_needed.composeapp.generated.resources.detail_field_label
import no_papers_needed.composeapp.generated.resources.detail_field_show_by_default
import no_papers_needed.composeapp.generated.resources.detail_field_type
import no_papers_needed.composeapp.generated.resources.detail_fields
import no_papers_needed.composeapp.generated.resources.detail_new_field
import no_papers_needed.composeapp.generated.resources.detail_orphan_fields
import no_papers_needed.composeapp.generated.resources.detail_remove_field
import no_papers_needed.composeapp.generated.resources.detail_title_hint
import org.jetbrains.compose.resources.stringResource

/**
 * The document's fields, driven by the registry: fields marked "show on every document" render
 * always, other registered fields render when this document carries a value (or the user just
 * added them), and values whose key has no definition sit in a muted group with a way to promote
 * the key into the registry. One "Add field" affordance covers both picking an existing
 * definition and defining a new one.
 */
@Composable
internal fun FieldsSection(
    doc: Doc,
    defs: List<FieldDef>,
    vocabulary: VocabularyState,
    edit: MetadataEditActions,
) {
    // Keys the user picked from "Add field" this session; they render empty until a value lands.
    var revealed by remember(doc.docId) { mutableStateOf(emptySet<String>()) }
    var newField by remember { mutableStateOf<NewFieldSeed?>(null) }
    val shown = defs.filter { it.showByDefault || it.key in doc.fields || it.key in revealed }
    val orphanKeys = (doc.fields.keys - defs.map { it.key }.toSet()).sorted()

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (def in shown) {
            key(def.key) {
                FieldRow(
                    def = def,
                    value = doc.fields[def.key],
                    suggestions = vocabulary.fieldValues[def.key].orEmpty(),
                    // The title stands in for the file name when empty, so the file name is its placeholder.
                    placeholder = doc.fileName.takeIf { def.key == FieldKeys.TITLE },
                    onCommit = { edit.onCommitField(def.key, it) },
                    onRemove = { edit.onRemoveField(def.key) }.takeIf { !def.showByDefault },
                )
                if (def.key == FieldKeys.TITLE) {
                    Text(
                        stringResource(Res.string.detail_title_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (orphanKeys.isNotEmpty()) {
            Text(
                stringResource(Res.string.detail_orphan_fields),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            for (orphan in orphanKeys) {
                key("orphan:$orphan") {
                    val value = doc.fields.getValue(orphan)
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Shown verbatim as text: without a definition there is no type to judge it by.
                        FieldEditor(
                            def = orphanDef(orphan),
                            value = value,
                            onCommit = { edit.onCommitField(orphan, it) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { newField = NewFieldSeed(key = orphan, label = orphan, value = value) }) {
                            Icon(
                                Icons.Filled.PlaylistAdd,
                                contentDescription = stringResource(Res.string.detail_add_to_fields),
                            )
                        }
                        IconButton(onClick = { edit.onRemoveField(orphan) }) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = stringResource(Res.string.detail_remove_field, orphan),
                            )
                        }
                    }
                }
            }
        }
        AddFieldButton(
            available = defs.filter { it !in shown },
            onPick = { revealed = revealed + it.key },
            onNew = { newField = NewFieldSeed() },
        )
    }

    newField?.let { seed ->
        NewFieldDialog(
            seed = seed,
            existing = defs,
            keySuggestions = vocabulary.orphanKeys,
            onConfirm = { def, value ->
                edit.onDefineField(def, value)
                newField = null
            },
            onDismiss = { newField = null },
        )
    }
}

@Composable
private fun FieldRow(
    def: FieldDef,
    value: String?,
    suggestions: List<String>,
    placeholder: String?,
    onCommit: (String) -> Unit,
    onRemove: (() -> Unit)?,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FieldEditor(
            def = def,
            value = value,
            suggestions = suggestions,
            placeholder = placeholder,
            onCommit = onCommit,
            modifier = Modifier.weight(1f),
        )
        if (onRemove != null) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.detail_remove_field, def.label))
            }
        }
    }
}

/** A stand-in definition for an unregistered key: plain text, labelled by its key. */
private fun orphanDef(key: String) =
    FieldDef(key = key, label = key, type = FieldType.TEXT, showByDefault = false, aiEnabled = false, position = 0)

/** "Add field": every definition not on screen, then "New field…". */
@Composable
private fun AddFieldButton(
    available: List<FieldDef>,
    onPick: (FieldDef) -> Unit,
    onNew: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Text(stringResource(Res.string.detail_add_field), modifier = Modifier.padding(start = 8.dp))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            available.forEach { def ->
                DropdownMenuItem(
                    text = { Text(def.label) },
                    onClick = {
                        expanded = false
                        onPick(def)
                    },
                )
            }
            DropdownMenuItem(
                text = { Text(stringResource(Res.string.detail_new_field) + "\u2026") },
                onClick = {
                    expanded = false
                    onNew()
                },
            )
        }
    }
}

/** What the "New field" dialog opens with: empty, or an orphan key being promoted with its value. */
internal data class NewFieldSeed(
    val key: String = "",
    val label: String = "",
    val value: String? = null,
)

/**
 * Define a field: label (the key is derived from it until edited by hand), type, optional hint,
 * "show on every document", and an optional first value for this document. Confirming writes
 * the definition and the value together through [onConfirm].
 */
@Composable
internal fun NewFieldDialog(
    seed: NewFieldSeed,
    existing: List<FieldDef>,
    keySuggestions: List<String>,
    onConfirm: (FieldDef, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var label by remember { mutableStateOf(seed.label) }
    var key by remember { mutableStateOf(seed.key) }
    var keyEdited by remember { mutableStateOf(seed.key.isNotEmpty()) }
    var type by remember { mutableStateOf(FieldType.TEXT) }
    var hint by remember { mutableStateOf("") }
    var showByDefault by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(seed.value.orEmpty()) }

    val normalizedKey = FieldKeys.normalize(key)
    val taken = normalizedKey != null && existing.any { FieldKeys.sameKey(it.key, normalizedKey) }
    val canSave = label.isNotBlank() && normalizedKey != null && !taken
    val draftDef =
        normalizedKey?.let {
            FieldDef(
                key = it,
                label = label.trim().ifEmpty { it },
                type = type,
                showByDefault = showByDefault,
                aiEnabled = false,
                position = (existing.maxOfOrNull { d -> d.position } ?: -1) + 1,
                hint = hint.trim().ifEmpty { null },
            )
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.detail_new_field)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = {
                        label = it
                        if (!keyEdited) key = FieldKeys.normalize(it).orEmpty()
                    },
                    label = { Text(stringResource(Res.string.detail_field_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                KeyField(
                    key = key,
                    taken = taken,
                    suggestions = keySuggestions.filter { s -> existing.none { FieldKeys.sameKey(it.key, s) } },
                    onChange = {
                        key = it
                        keyEdited = true
                    },
                )
                TypeDropdown(type) { type = it }
                OutlinedTextField(
                    value = hint,
                    onValueChange = { hint = it },
                    label = { Text(stringResource(Res.string.detail_field_hint)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                ShowByDefaultSwitch(showByDefault) { showByDefault = it }
                // The value editor follows the chosen type, so what gets saved is already canonical.
                draftDef?.let { def ->
                    key(def.type) {
                        FieldEditor(def = def, value = value, onCommit = { value = it })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { draftDef?.let { onConfirm(it, value.ifBlank { null }) } },
                enabled = canSave,
            ) { Text(stringResource(Res.string.common_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}

@Composable
private fun ShowByDefaultSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(Res.string.detail_field_show_by_default))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun KeyField(
    key: String,
    taken: Boolean,
    suggestions: List<String>,
    onChange: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val matching = suggestions.filter { key.isBlank() || it.contains(key, ignoreCase = true) }
    Box {
        OutlinedTextField(
            value = key,
            onValueChange = {
                onChange(it)
                expanded = matching.isNotEmpty()
            },
            label = { Text(stringResource(Res.string.detail_field_key)) },
            isError = taken,
            supportingText = if (taken) ({ Text(stringResource(Res.string.detail_field_key_taken)) }) else null,
            singleLine = true,
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded && matching.isNotEmpty(), onDismissRequest = { expanded = false }) {
            matching.forEach { suggestion ->
                DropdownMenuItem(
                    text = { Text(suggestion) },
                    onClick = {
                        expanded = false
                        onChange(suggestion)
                    },
                )
            }
        }
    }
}

@Composable
private fun TypeDropdown(
    current: FieldType,
    onSelect: (FieldType) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        Text(stringResource(Res.string.detail_field_type), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { expanded = true }) {
            Text(fieldTypeLabel(current))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FieldType.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(fieldTypeLabel(option)) },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    },
                )
            }
        }
    }
}
