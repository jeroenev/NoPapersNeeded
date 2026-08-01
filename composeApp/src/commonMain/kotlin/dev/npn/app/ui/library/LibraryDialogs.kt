@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_apply
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.library_tag
import org.jetbrains.compose.resources.stringResource

/** Tag pick dialog with autocomplete from existing tag names (used for bulk add/remove). */
@Composable
fun TagPickerDialog(
    title: String,
    allTagNames: List<String>,
    confirmLabel: String? = null,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    val suggestions =
        remember(text, allTagNames) {
            if (text.isBlank()) {
                allTagNames
            } else {
                allTagNames.filter { it.contains(text, ignoreCase = true) }
            }
        }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text(stringResource(Res.string.library_tag)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 200.dp)) {
                    items(suggestions, key = { it }) { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { text = name }
                                    .padding(vertical = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { onConfirm(text.trim()) },
            ) { Text(confirmLabel ?: stringResource(Res.string.common_apply)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}
