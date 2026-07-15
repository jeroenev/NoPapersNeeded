@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package dev.npn.app.ui.common

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * Inline tag editor: current tags as removable chips plus an add field with autocomplete over
 * [allTagNames]. Tag paths are dot-separated hierarchical namespaces (e.g. "finance.invoices");
 * suggestions match anywhere in the path, case-insensitive.
 */
@Composable
fun TagEditor(
    tags: List<String>,
    allTagNames: List<String>,
    tagColors: Map<String, String?>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var input by remember { mutableStateOf("") }

    val suggestions = remember(input, allTagNames, tags) {
        val q = input.trim()
        if (q.isEmpty()) emptyList()
        else allTagNames
            .filter { it !in tags && it.contains(q, ignoreCase = true) }
            .take(8)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(tag) },
                        label = { Text(tag) },
                        leadingIcon = {
                            val c = parseHexColor(tagColors[tag])
                            if (c != null) {
                                androidx.compose.foundation.layout.Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(androidx.compose.foundation.shape.CircleShape)
                                        .background(c),
                                )
                            }
                        },
                        trailingIcon = { Icon(Icons.Filled.Clear, contentDescription = "Remove $tag") },
                    )
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Add tag") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (input.isNotBlank()) {
                    IconButton(onClick = {
                        onAdd(input.trim())
                        input = ""
                    }) { Icon(Icons.Filled.Add, contentDescription = "Add tag") }
                }
            },
        )

        if (suggestions.isNotEmpty()) {
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.small,
                modifier = Modifier.fillMaxWidth(),
            ) {
                LazyColumn(Modifier.heightIn(max = 160.dp)) {
                    items(suggestions, key = { it }) { name ->
                        Text(
                            text = name,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAdd(name)
                                    input = ""
                                }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        } else if (input.isNotBlank()) {
            Text(
                "Press + to create \"${input.trim()}\"",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
