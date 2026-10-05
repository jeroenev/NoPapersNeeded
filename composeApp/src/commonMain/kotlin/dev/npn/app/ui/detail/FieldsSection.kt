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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.FieldDefForm
import dev.npn.app.ui.common.FieldDefFormState
import dev.npn.app.ui.common.FieldEditor
import dev.npn.app.ui.common.HelpText
import dev.npn.core.app.Doc
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldParse
import dev.npn.core.model.FieldType
import dev.npn.core.model.FieldValues
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_save
import no_papers_needed.composeapp.generated.resources.detail_add_field
import no_papers_needed.composeapp.generated.resources.detail_add_to_fields
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
    actions: MetadataActions,
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
                    onCommit = { actions.commitField(def.key, it) },
                    onRemove = { actions.removeField(def.key) }.takeIf { !def.showByDefault },
                )
                if (def.key == FieldKeys.TITLE) {
                    HelpText(stringResource(Res.string.detail_title_hint))
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
                            onCommit = { actions.commitField(orphan, it) },
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = { newField = NewFieldSeed(key = orphan, label = orphan, value = value) }) {
                            Icon(
                                Icons.Filled.PlaylistAdd,
                                contentDescription = stringResource(Res.string.detail_add_to_fields),
                            )
                        }
                        IconButton(onClick = { actions.removeField(orphan) }) {
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
                actions.defineField(def, value)
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
 * Define a field from the document panel: the shared [FieldDefForm] plus an optional first value
 * for this document, edited with the control of the chosen type so what gets saved is already
 * canonical. Confirming writes the definition and the value together through [onConfirm], and is
 * blocked while the value does not fit the chosen type (a type switch drops a value it refuses).
 */
@Composable
internal fun NewFieldDialog(
    seed: NewFieldSeed,
    existing: List<FieldDef>,
    keySuggestions: List<String>,
    onConfirm: (FieldDef, String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var form by remember {
        mutableStateOf(FieldDefFormState(label = seed.label, key = seed.key, keyEdited = seed.key.isNotEmpty()))
    }
    var value by remember { mutableStateOf(seed.value.orEmpty()) }
    val draftDef = form.toDef(position = (existing.maxOfOrNull { it.position } ?: -1) + 1)
    val valueValid = value.isBlank() || FieldValues.canonicalize(form.type, value) is FieldParse.Ok
    val canSave = form.label.isNotBlank() && draftDef != null && !form.collides(existing) && valueValid
    // Switching type can strand the typed value: drop it when the new type refuses it, so the
    // dialog never confirms a definition whose first value would fail to write (the definition is
    // committed before the value, so a rejected value would otherwise leave the field half-applied).
    LaunchedEffect(form.type) {
        if (value.isNotBlank() && FieldValues.canonicalize(form.type, value) is FieldParse.Invalid) {
            value = ""
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.detail_new_field)) },
        text = {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FieldDefForm(
                    state = form,
                    onChange = { form = it },
                    existing = existing,
                    keySuggestions = keySuggestions,
                )
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
