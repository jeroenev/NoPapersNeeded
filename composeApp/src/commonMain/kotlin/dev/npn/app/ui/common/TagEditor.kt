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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.tags.TagFormDialog
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_add_tag
import no_papers_needed.composeapp.generated.resources.common_create
import no_papers_needed.composeapp.generated.resources.common_create_tag_hint
import no_papers_needed.composeapp.generated.resources.common_create_tag_with_color
import no_papers_needed.composeapp.generated.resources.common_remove_tag
import no_papers_needed.composeapp.generated.resources.common_tags_new
import org.jetbrains.compose.resources.stringResource

/**
 * Inline tag editor: current tags as removable chips plus an add field with autocomplete over
 * [allTagNames]. Tag paths are dot-separated hierarchical namespaces (e.g. "finance.invoices");
 * suggestions match anywhere in the path, case-insensitive.
 *
 * Tags are created here, on the document, simply by typing a new name. When [onAddWithStyle] is
 * provided, a brand-new name additionally offers a "create with color" path so the style can be
 * chosen in the same gesture (there is no separate create-tag flow anymore: unused tags are
 * pruned automatically, so a tag only exists while a document carries it).
 */
@Composable
fun TagEditor(
    tags: List<String>,
    allTagNames: List<String>,
    tagColors: Map<String, String?>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
    onAddWithStyle: ((name: String, color: String?) -> Unit)? = null,
) {
    var input by remember { mutableStateOf("") }
    var styleFor by remember { mutableStateOf<String?>(null) }
    val addTagLabel = stringResource(Res.string.common_add_tag)

    val suggestions =
        remember(input, allTagNames, tags) {
            val q = input.trim()
            if (q.isEmpty()) {
                emptyList()
            } else {
                allTagNames
                    .filter { it !in tags && it.contains(q, ignoreCase = true) }
                    .take(MAX_INLINE_SUGGESTIONS)
            }
        }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (tags.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                tags.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { onRemove(tag) },
                        label = { Text(tag) },
                        leadingIcon = { TagColorDot(tagColors[tag]) },
                        trailingIcon = {
                            Icon(Icons.Filled.Clear, stringResource(Res.string.common_remove_tag, tag))
                        },
                    )
                }
            }
        }

        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text(addTagLabel) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            trailingIcon = {
                if (input.isNotBlank()) {
                    IconButton(onClick = {
                        onAdd(input.trim())
                        input = ""
                    }) { Icon(Icons.Filled.Add, contentDescription = addTagLabel) }
                }
            },
        )

        TagInputSuggestions(
            input = input,
            suggestions = suggestions,
            onPick = { name ->
                onAdd(name)
                input = ""
            },
        )

        val trimmed = input.trim()
        if (onAddWithStyle != null && trimmed.isNotEmpty() && trimmed !in allTagNames) {
            TextButton(onClick = { styleFor = trimmed }) {
                Text(stringResource(Res.string.common_create_tag_with_color, trimmed))
            }
        }
    }

    styleFor?.let { name ->
        TagFormDialog(
            title = stringResource(Res.string.common_tags_new),
            initialName = name,
            initialColor = null,
            confirmLabel = stringResource(Res.string.common_create),
            onConfirm = { finalName, color ->
                onAddWithStyle?.invoke(finalName, color)
                styleFor = null
                input = ""
            },
            onDismiss = { styleFor = null },
        )
    }
}

/** Autocomplete list under the add field, or the create hint while the name is brand-new. */
@Composable
private fun TagInputSuggestions(
    input: String,
    suggestions: List<String>,
    onPick: (String) -> Unit,
) {
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
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(name) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                    )
                }
            }
        }
    } else if (input.isNotBlank()) {
        Text(
            stringResource(Res.string.common_create_tag_hint, input.trim()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun TagColorDot(color: String?) {
    parseHexColor(color)?.let {
        androidx.compose.foundation.layout.Box(
            Modifier
                .size(10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(it),
        )
    }
}
