package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import kotlinx.serialization.Serializable
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_field_ai_enabled
import no_papers_needed.composeapp.generated.resources.detail_field_hint
import no_papers_needed.composeapp.generated.resources.detail_field_key
import no_papers_needed.composeapp.generated.resources.detail_field_key_taken
import no_papers_needed.composeapp.generated.resources.detail_field_label
import no_papers_needed.composeapp.generated.resources.detail_field_show_by_default
import no_papers_needed.composeapp.generated.resources.detail_field_type
import no_papers_needed.composeapp.generated.resources.detail_field_type_locked
import org.jetbrains.compose.resources.stringResource

/**
 * What the user has typed into a field-definition form. [keyEdited] records that the key was set
 * by hand, after which it stops following the label. [perDocument] is not on the form; it is
 * carried so that editing a definition keeps it. Serializable, so an open form survives recreation.
 */
@Serializable
data class FieldDefFormState(
    val label: String = "",
    val key: String = "",
    val keyEdited: Boolean = false,
    val type: FieldType = FieldType.TEXT,
    val hint: String = "",
    val showByDefault: Boolean = false,
    val aiEnabled: Boolean = false,
    val perDocument: Boolean = false,
) {
    val normalizedKey: String? get() = FieldKeys.normalize(key)

    /** Whether [normalizedKey] would collide with a definition other than [own]. */
    fun collides(
        existing: List<FieldDef>,
        own: String? = null,
    ): Boolean {
        val key = normalizedKey ?: return false
        return existing.any { it.key != own && FieldKeys.sameKey(it.key, key) }
    }

    /** Whether the form describes a definition that can be saved beside [existing] (editing [own]). */
    fun canSave(
        existing: List<FieldDef>,
        own: String? = null,
    ): Boolean = label.isNotBlank() && normalizedKey != null && !collides(existing, own)

    /**
     * The definition this form describes, or null while the key is unusable. A new definition keeps
     * [FieldDef.PLACE_LAST], so the registry puts it after the last.
     */
    fun toDef(position: Int = FieldDef.PLACE_LAST): FieldDef? {
        val key = normalizedKey ?: return null
        return FieldDef(
            key = key,
            label = label.trim().ifEmpty { key },
            type = type,
            showByDefault = showByDefault,
            aiEnabled = aiEnabled,
            position = position,
            hint = hint.trim().ifEmpty { null },
            perDocument = perDocument,
        )
    }

    companion object {
        fun of(def: FieldDef) =
            FieldDefFormState(
                label = def.label,
                key = def.key,
                keyEdited = true,
                type = def.type,
                hint = def.hint.orEmpty(),
                showByDefault = def.showByDefault,
                aiEnabled = def.aiEnabled,
                perDocument = def.perDocument,
            )
    }
}

/**
 * The shared form for defining or editing a field: label, key (derived from the label until
 * edited; frozen with [lockKey] once a definition exists, because the key is the identity values
 * are stored under), type (frozen with [lockType] for the well-known keys other subsystems rely
 * on), hint, and the two flags. [keySuggestions] offers unregistered keys already in use.
 */
@Composable
fun FieldDefForm(
    state: FieldDefFormState,
    onChange: (FieldDefFormState) -> Unit,
    existing: List<FieldDef>,
    modifier: Modifier = Modifier,
    ownKey: String? = null,
    lockKey: Boolean = false,
    lockType: Boolean = false,
    keySuggestions: List<String> = emptyList(),
    typeWarning: String? = null,
) {
    val taken = state.collides(existing, ownKey)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.label,
            onValueChange = {
                val derived = if (state.keyEdited || lockKey) state.key else FieldKeys.normalize(it).orEmpty()
                onChange(state.copy(label = it, key = derived))
            },
            label = { Text(stringResource(Res.string.detail_field_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        KeyField(
            key = state.key,
            taken = taken,
            enabled = !lockKey,
            suggestions = keySuggestions.filter { s -> existing.none { FieldKeys.sameKey(it.key, s) } },
            onChange = { onChange(state.copy(key = it, keyEdited = true)) },
        )
        TypeDropdown(
            current = state.type,
            enabled = !lockType,
            note = if (lockType) stringResource(Res.string.detail_field_type_locked) else typeWarning,
            onSelect = { onChange(state.copy(type = it)) },
        )
        OutlinedTextField(
            value = state.hint,
            onValueChange = { onChange(state.copy(hint = it)) },
            label = { Text(stringResource(Res.string.detail_field_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SwitchRow(
            label = stringResource(Res.string.detail_field_show_by_default),
            checked = state.showByDefault,
            onCheckedChange = { onChange(state.copy(showByDefault = it)) },
        )
        SwitchRow(
            label = stringResource(Res.string.detail_field_ai_enabled),
            checked = state.aiEnabled,
            onCheckedChange = { onChange(state.copy(aiEnabled = it)) },
        )
    }
}

@Composable
private fun KeyField(
    key: String,
    taken: Boolean,
    enabled: Boolean,
    suggestions: List<String>,
    onChange: (String) -> Unit,
) {
    SuggestField(
        value = key,
        onValueChange = onChange,
        suggestions = suggestions,
        label = stringResource(Res.string.detail_field_key),
        enabled = enabled,
        isError = taken,
        supportingText = if (taken) stringResource(Res.string.detail_field_key_taken) else null,
    )
}

@Composable
private fun TypeDropdown(
    current: FieldType,
    enabled: Boolean,
    note: String?,
    onSelect: (FieldType) -> Unit,
) {
    Column {
        Dropdown(
            selected = fieldTypeLabel(current),
            options = FieldType.entries,
            optionLabel = { fieldTypeLabel(it) },
            onSelect = onSelect,
            label = stringResource(Res.string.detail_field_type),
            enabled = enabled,
        )
        note?.let { HelpText(it) }
    }
}
