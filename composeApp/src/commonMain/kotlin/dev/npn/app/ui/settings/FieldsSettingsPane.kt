package dev.npn.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.CheckboxRow
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.FieldDefForm
import dev.npn.app.ui.common.FieldDefFormState
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.fieldTypeLabel
import dev.npn.app.ui.common.jsonSaver
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import no_papers_needed.composeapp.generated.resources.Res
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
 * The one dialog the field manager has open, or none. Edit and Delete name their field by key and
 * are resolved against the current registry, so a field edited on another device meanwhile shows
 * as it is now. Saved as JSON, so it is open again after the screen is recreated.
 */
@Serializable
private sealed interface FieldDialog {
    @Serializable
    data object Add : FieldDialog

    @Serializable
    data class Edit(
        val key: String,
    ) : FieldDialog

    @Serializable
    data class Delete(
        val key: String,
    ) : FieldDialog
}

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
    var dialog by rememberSaveable(stateSaver = jsonSaver(FieldDialog.serializer().nullable)) { mutableStateOf(null) }

    HelpText(stringResource(Res.string.common_settings_document_fields_help))
    fields.forEachIndexed { index, def ->
        key(def.key) {
            FieldRow(
                def = def,
                count = usage[def.key] ?: 0,
                canMoveUp = index > 0,
                canMoveDown = index < fields.lastIndex,
                onEdit = { dialog = FieldDialog.Edit(def.key) },
                onMove = { onMove(def.key, it) },
                onDelete = { dialog = FieldDialog.Delete(def.key) },
            )
        }
    }
    OutlinedButton(onClick = { dialog = FieldDialog.Add }) {
        Icon(Icons.Filled.Add, contentDescription = null)
        Text(stringResource(Res.string.detail_add_field), modifier = Modifier.padding(start = 8.dp))
    }
    HelpText(stringResource(Res.string.common_settings_syncs_across_devices))

    val open = dialog ?: return
    val close = { dialog = null }
    val key =
        when (open) {
            FieldDialog.Add -> null
            is FieldDialog.Edit -> open.key
            is FieldDialog.Delete -> open.key
        }
    val def = key?.let { wanted -> fields.firstOrNull { it.key == wanted } }
    if (key != null && def == null) {
        // The field went away (removed on another device) while its dialog was open. An empty
        // registry is one still loading, as after the screen was recreated: never a library's.
        if (fields.isNotEmpty()) LaunchedEffect(key) { close() }
        return
    }
    when (open) {
        FieldDialog.Add -> {
            FieldDefDialog(
                existing = fields,
                def = null,
                usage = 0,
                onConfirm = {
                    onSave(it)
                    close()
                },
                onDismiss = close,
            )
        }

        is FieldDialog.Edit -> {
            FieldDefDialog(
                existing = fields,
                def = def,
                usage = usage[open.key] ?: 0,
                onConfirm = {
                    onSave(it)
                    close()
                },
                onDismiss = close,
            )
        }

        is FieldDialog.Delete -> {
            DeleteFieldDialog(
                def = checkNotNull(def),
                usage = usage[open.key] ?: 0,
                onConfirm = { clear ->
                    onRemove(open.key, clear)
                    close()
                },
                onDismiss = close,
            )
        }
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
        TooltipIconButton(
            Icons.Filled.KeyboardArrowUp,
            stringResource(Res.string.common_settings_field_move_up),
            onClick = { onMove(-1) },
            enabled = canMoveUp,
        )
        TooltipIconButton(
            Icons.Filled.KeyboardArrowDown,
            stringResource(Res.string.common_settings_field_move_down),
            onClick = { onMove(1) },
            enabled = canMoveDown,
        )
        TooltipIconButton(Icons.Filled.Delete, stringResource(Res.string.common_delete), onClick = onDelete)
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
    var form by rememberSaveable(stateSaver = jsonSaver(FieldDefFormState.serializer())) {
        mutableStateOf(def?.let(FieldDefFormState::of) ?: FieldDefFormState())
    }
    // A type change on a field that still carries values is held back until the user confirms:
    // existing values are not converted, so this is the one edit in the pane that can leave
    // off-type data behind. The form cannot change under the confirmation, so it confirms [result].
    var confirmingTypeChange by rememberSaveable { mutableStateOf(false) }
    val result = form.toDef(def?.position ?: FieldDef.PLACE_LAST)
    val canSave = form.canSave(existing, own = def?.key)
    val changesTypeOfUsedField = def != null && usage > 0 && result != null && result.type != def.type

    ConfirmDialog(
        title =
            stringResource(
                if (def ==
                    null
                ) {
                    Res.string.detail_new_field
                } else {
                    Res.string.common_settings_field_edit_title
                },
            ),
        onConfirm = {
            result?.let { next -> if (changesTypeOfUsedField) confirmingTypeChange = true else onConfirm(next) }
        },
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.common_save),
        confirmEnabled = canSave,
    ) {
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
    }

    if (confirmingTypeChange && result != null && def != null) {
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
                confirmingTypeChange = false
                onConfirm(result)
            },
            onDismiss = { confirmingTypeChange = false },
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
    var clearValues by rememberSaveable { mutableStateOf(false) }
    ConfirmDialog(
        title = stringResource(Res.string.common_settings_field_delete_title),
        onConfirm = { onConfirm(clearValues) },
        onDismiss = onDismiss,
        confirmLabel = stringResource(Res.string.common_delete),
        destructive = true,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(
                    Res.string.common_settings_field_delete_message,
                    def.label,
                    pluralStringResource(Res.plurals.common_settings_field_usage, usage, usage),
                ),
            )
            if (usage > 0) {
                CheckboxRow(
                    label = stringResource(Res.string.common_settings_field_clear_values),
                    checked = clearValues,
                    onCheckedChange = { clearValues = it },
                )
            }
        }
    }
}
