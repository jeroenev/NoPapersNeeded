package dev.npn.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.FieldDefForm
import dev.npn.app.ui.common.FieldDefFormState
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.fieldTypeLabel
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_delete
import no_papers_needed.composeapp.generated.resources.common_edit
import no_papers_needed.composeapp.generated.resources.common_save
import no_papers_needed.composeapp.generated.resources.common_settings_document_fields_help
import no_papers_needed.composeapp.generated.resources.common_settings_field_clear_values
import no_papers_needed.composeapp.generated.resources.common_settings_field_delete_message
import no_papers_needed.composeapp.generated.resources.common_settings_field_delete_title
import no_papers_needed.composeapp.generated.resources.common_settings_field_edit_title
import no_papers_needed.composeapp.generated.resources.common_settings_field_move_down
import no_papers_needed.composeapp.generated.resources.common_settings_field_move_up
import no_papers_needed.composeapp.generated.resources.common_settings_field_summary
import no_papers_needed.composeapp.generated.resources.common_settings_field_type_change_message
import no_papers_needed.composeapp.generated.resources.common_settings_field_type_change_title
import no_papers_needed.composeapp.generated.resources.common_settings_field_type_change_warning
import no_papers_needed.composeapp.generated.resources.common_settings_field_usage
import no_papers_needed.composeapp.generated.resources.common_settings_syncs_across_devices
import no_papers_needed.composeapp.generated.resources.detail_add_field
import no_papers_needed.composeapp.generated.resources.detail_new_field
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The library's field manager: every registered field in display order with its label, key, type
 * and usage count. Tapping a row edits it; the arrows reorder; the bin deletes after a warning
 * that names the usage count and offers to clear the values too. New fields are added at the end.
 */
@Composable
internal fun FieldsSettingsPane(
    fields: List<FieldDef>,
    usage: Map<String, Int>,
    onSave: (FieldDef) -> Unit,
    onRemove: (key: String, clearValues: Boolean) -> Unit,
    onMove: (key: String, delta: Int) -> Unit,
) {
    var editing by remember { mutableStateOf<FieldDef?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<FieldDef?>(null) }

    HelpText(stringResource(Res.string.common_settings_document_fields_help))
    fields.forEachIndexed { index, def ->
        key(def.key) {
            FieldRow(
                def = def,
                count = usage[def.key] ?: 0,
                canMoveUp = index > 0,
                canMoveDown = index < fields.lastIndex,
                onEdit = { editing = def },
                onMove = { onMove(def.key, it) },
                onDelete = { deleting = def },
            )
        }
    }
    OutlinedButton(onClick = { adding = true }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(stringResource(Res.string.detail_add_field), modifier = Modifier.padding(start = 8.dp))
    }
    HelpText(stringResource(Res.string.common_settings_syncs_across_devices))

    if (adding) {
        FieldDefDialog(
            existing = fields,
            def = null,
            usage = 0,
            onConfirm = {
                onSave(it)
                adding = false
            },
            onDismiss = { adding = false },
        )
    }
    editing?.let { def ->
        FieldDefDialog(
            existing = fields,
            def = def,
            usage = usage[def.key] ?: 0,
            onConfirm = {
                onSave(it)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
    deleting?.let { def ->
        DeleteFieldDialog(
            def = def,
            usage = usage[def.key] ?: 0,
            onConfirm = { clear ->
                onRemove(def.key, clear)
                deleting = null
            },
            onDismiss = { deleting = null },
        )
    }
}

@Composable
private fun FieldRow(
    def: FieldDef,
    count: Int,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMove: (Int) -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(
                onClick = onEdit,
                role = Role.Button,
                onClickLabel = stringResource(Res.string.common_edit),
            ).padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(def.label, style = MaterialTheme.typography.bodyLarge)
            HelpText(
                stringResource(
                    Res.string.common_settings_field_summary,
                    def.key,
                    fieldTypeLabel(def.type),
                    pluralStringResource(Res.plurals.common_settings_field_usage, count, count),
                ),
            )
        }
        IconButton(onClick = { onMove(-1) }, enabled = canMoveUp) {
            Icon(
                Icons.Filled.KeyboardArrowUp,
                contentDescription = stringResource(Res.string.common_settings_field_move_up),
            )
        }
        IconButton(onClick = { onMove(1) }, enabled = canMoveDown) {
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = stringResource(Res.string.common_settings_field_move_down),
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(Res.string.common_delete))
        }
    }
}

/**
 * Add or edit a definition. An existing field keeps its key (values are stored under it) and a
 * well-known field keeps its type (other subsystems read it as that type); changing the type of a
 * field is allowed but warns, and when the field still carries values on documents it is held back
 * behind a confirmation naming that usage.
 */
@Composable
private fun FieldDefDialog(
    existing: List<FieldDef>,
    def: FieldDef?,
    usage: Int,
    onConfirm: (FieldDef) -> Unit,
    onDismiss: () -> Unit,
) {
    var form by remember { mutableStateOf(def?.let(FieldDefFormState::of) ?: FieldDefFormState()) }
    // A type change on a field that still carries values is held back until the user confirms:
    // existing values are not converted, so this is the one edit in the pane that can leave
    // off-type data behind.
    var pendingTypeChange by remember { mutableStateOf<FieldDef?>(null) }
    val position = def?.position ?: ((existing.maxOfOrNull { it.position } ?: -1) + 1)
    val result = form.toDef(position)
    val canSave = form.canSave(existing, own = def?.key)
    val changesTypeOfUsedField = def != null && usage > 0 && result != null && result.type != def.type

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            val title = if (def == null) Res.string.detail_new_field else Res.string.common_settings_field_edit_title
            Text(stringResource(title))
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                FieldDefForm(
                    state = form,
                    onChange = { form = it },
                    existing = existing,
                    ownKey = def?.key,
                    lockKey = def != null,
                    lockType = def != null && def.key in FieldKeys.WELL_KNOWN,
                    typeWarning =
                        if (def != null && usage > 0 && form.type != def.type) {
                            stringResource(Res.string.common_settings_field_type_change_warning)
                        } else {
                            null
                        },
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val next = result ?: return@TextButton
                    if (changesTypeOfUsedField) pendingTypeChange = next else onConfirm(next)
                },
                enabled = canSave,
            ) {
                Text(stringResource(Res.string.common_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )

    val pending = pendingTypeChange
    if (pending != null && def != null) {
        ConfirmDialog(
            title = stringResource(Res.string.common_settings_field_type_change_title),
            message =
                pluralStringResource(
                    Res.plurals.common_settings_field_type_change_message,
                    usage,
                    def.label,
                    usage,
                ),
            onConfirm = {
                pendingTypeChange = null
                onConfirm(pending)
            },
            onDismiss = { pendingTypeChange = null },
        )
    }
}

@Composable
private fun DeleteFieldDialog(
    def: FieldDef,
    usage: Int,
    onConfirm: (clearValues: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var clearValues by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.common_settings_field_delete_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(
                        Res.string.common_settings_field_delete_message,
                        def.label,
                        pluralStringResource(Res.plurals.common_settings_field_usage, usage, usage),
                    ),
                )
                if (usage > 0) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .toggleable(
                                value = clearValues,
                                role = Role.Checkbox,
                                onValueChange = { clearValues = it },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = clearValues, onCheckedChange = null)
                        Text(stringResource(Res.string.common_settings_field_clear_values))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(clearValues) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(Res.string.common_delete)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) } },
    )
}
