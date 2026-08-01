@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties
import dev.npn.app.ui.common.MAX_INLINE_SUGGESTIONS
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_add_field
import no_papers_needed.composeapp.generated.resources.detail_fields
import no_papers_needed.composeapp.generated.resources.detail_new_field
import no_papers_needed.composeapp.generated.resources.detail_remove_field
import no_papers_needed.composeapp.generated.resources.detail_value
import org.jetbrains.compose.resources.stringResource

/** Editable custom metadata plus the row used to add a new key/value pair. */
@Composable
internal fun CustomFieldsSection(
    fields: Map<String, String>,
    fieldKeys: List<String>,
    onCommitField: (key: String, value: String) -> Unit,
    onRemoveField: (key: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(Res.string.detail_fields),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        // Stable keys keep each field's edit buffer attached to its metadata key after deletion.
        for (fieldKey in fields.keys.sorted()) {
            key(fieldKey) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommitTextField(
                        label = fieldKey,
                        initial = fields.getValue(fieldKey),
                        onCommit = { onCommitField(fieldKey, it) },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemoveField(fieldKey) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(Res.string.detail_remove_field, fieldKey),
                        )
                    }
                }
            }
        }
        AddFieldRow(fields.keys, fieldKeys, onCommitField)
    }
}

@Composable
private fun AddFieldRow(
    existingKeys: Set<String>,
    fieldKeys: List<String>,
    onAdd: (key: String, value: String) -> Unit,
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    val suggestions =
        remember(key, fieldKeys, existingKeys) {
            val query = key.trim()
            fieldKeys
                .filter {
                    it !in existingKeys && (query.isEmpty() || it.contains(query, ignoreCase = true))
                }.take(MAX_INLINE_SUGGESTIONS)
        }
    val canAdd = key.isNotBlank() && value.isNotBlank()

    fun commit() {
        if (canAdd) {
            onAdd(key.trim(), value.trim())
            key = ""
            value = ""
            expanded = false
        }
    }

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = key,
                onValueChange = {
                    key = it
                    expanded = it.isNotBlank() && suggestions.isNotEmpty()
                },
                label = { Text(stringResource(Res.string.detail_new_field)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = expanded && suggestions.isNotEmpty(),
                onDismissRequest = { expanded = false },
                properties = PopupProperties(focusable = false),
            ) {
                suggestions.forEach { suggestion ->
                    DropdownMenuItem(
                        text = { Text(suggestion) },
                        onClick = {
                            key = suggestion
                            expanded = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(stringResource(Res.string.detail_value)) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = ::commit, enabled = canAdd) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(Res.string.detail_add_field))
        }
    }
}
