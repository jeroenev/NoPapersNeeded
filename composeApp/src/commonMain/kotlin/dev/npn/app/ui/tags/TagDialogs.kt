@file:OptIn(ExperimentalLayoutApi::class)

package dev.npn.app.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.parseHexColor

private val Palette = listOf(
    "#4C5CC7", "#2E7D32", "#C62828", "#EF6C00", "#6A1B9A",
    "#00838F", "#AD1457", "#5D4037", "#455A64", "#F9A825",
)

/** Create/edit a tag with an optional name field and a color palette. */
@Composable
fun TagFormDialog(
    title: String,
    initialName: String,
    initialColor: String?,
    confirmLabel: String,
    nameEditable: Boolean = true,
    onConfirm: (name: String, color: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name (dot paths for hierarchy)") },
                    singleLine = true,
                    enabled = nameEditable,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Color", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // "None" swatch.
                    ColorSwatch(
                        color = null,
                        selected = color == null,
                        onClick = { color = null },
                    )
                    Palette.forEach { hex ->
                        ColorSwatch(
                            color = hex,
                            selected = color?.equals(hex, ignoreCase = true) == true,
                            onClick = { color = hex },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onConfirm(name.trim(), color) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ColorSwatch(color: String?, selected: Boolean, onClick: () -> Unit) {
    val c = parseHexColor(color) ?: MaterialTheme.colorScheme.surfaceVariant
    androidx.compose.foundation.layout.Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(c)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (color == null) {
            Text("x", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
        }
    }
}

/** Prefilled single-value text dialog (used for rename). */
@Composable
fun SingleValueDialog(
    title: String,
    label: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank() && text.trim() != initial,
                onClick = { onConfirm(text.trim()) },
            ) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick an existing tag from a list (used for merge target). */
@Composable
fun TagPickDialog(
    title: String,
    options: List<String>,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            LazyColumn(Modifier.heightIn(max = 300.dp)) {
                items(options, key = { it }) { name ->
                    androidx.compose.foundation.layout.Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { selected = name }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        if (selected == name) {
                            Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        } else {
                            androidx.compose.foundation.layout.Box(Modifier.size(24.dp))
                        }
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { onConfirm(selected!!) }) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
