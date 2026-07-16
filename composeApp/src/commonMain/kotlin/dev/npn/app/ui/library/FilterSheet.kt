@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.TagInfo

/**
 * Filter panel. Tag multi-select is split into three sections (AND/OR/NOT); cycling a tag
 * chip moves it OFF -> ALL -> ANY -> NONE -> OFF so each tag lands in exactly one bucket.
 */
@Composable
fun FilterSheet(
    filter: LibraryFilter,
    tags: List<TagInfo>,
    correspondents: List<String>,
    docTypes: List<String>,
    onApply: (LibraryFilter) -> Unit,
    onClearAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(filter) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Filters", style = MaterialTheme.typography.titleLarge)
                TextButton(onClick = {
                    draft = LibraryFilter()
                    onClearAll()
                }) { Text("Clear all") }
            }

            SectionLabel("Tags (tap to cycle: all -> any -> none)")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tags.forEach { tag ->
                    val bucket = tagBucket(draft, tag.name)
                    FilterChip(
                        selected = bucket != TagBucket.OFF,
                        onClick = { draft = cycleTag(draft, tag.name) },
                        label = { Text(bucketPrefix(bucket) + tag.name) },
                    )
                }
                if (tags.isEmpty()) {
                    Text(
                        "No tags yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            SectionLabel("Reviewed")
            EnumDropdown(
                current = when (draft.reviewed) {
                    true -> "Reviewed"
                    false -> "Needs review"
                    null -> "Any"
                },
                options = listOf("Any", "Reviewed", "Needs review"),
                onSelect = { label ->
                    draft = draft.copy(
                        reviewed = when (label) {
                            "Reviewed" -> true
                            "Needs review" -> false
                            else -> null
                        },
                    )
                },
            )

            SectionLabel("Correspondent")
            EnumDropdown(
                current = draft.correspondent ?: "Any",
                options = listOf("Any") + correspondents,
                onSelect = { draft = draft.copy(correspondent = it.takeIf { v -> v != "Any" }) },
            )

            SectionLabel("Document type")
            EnumDropdown(
                current = draft.docType ?: "Any",
                options = listOf("Any") + docTypes,
                onSelect = { draft = draft.copy(docType = it.takeIf { v -> v != "Any" }) },
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Untagged only")
                Switch(
                    checked = draft.untaggedOnly,
                    onCheckedChange = { draft = draft.copy(untaggedOnly = it) },
                )
            }

            OutlinedButton(
                onClick = { onApply(draft) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Apply filters") }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun EnumDropdown(
    current: String,
    options: List<String>,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column {
        OutlinedButton(onClick = { expanded = true }) {
            Text(current)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = {
                        onSelect(opt)
                        expanded = false
                    },
                )
            }
        }
    }
}

private enum class TagBucket { OFF, ALL, ANY, NONE }

private fun tagBucket(f: LibraryFilter, tag: String): TagBucket = when (tag) {
    in f.tagsAll -> TagBucket.ALL
    in f.tagsAny -> TagBucket.ANY
    in f.tagsNone -> TagBucket.NONE
    else -> TagBucket.OFF
}

private fun bucketPrefix(b: TagBucket): String = when (b) {
    TagBucket.OFF -> ""
    TagBucket.ALL -> "AND "
    TagBucket.ANY -> "OR "
    TagBucket.NONE -> "NOT "
}

private fun cycleTag(f: LibraryFilter, tag: String): LibraryFilter {
    val stripped = f.copy(
        tagsAll = f.tagsAll - tag,
        tagsAny = f.tagsAny - tag,
        tagsNone = f.tagsNone - tag,
    )
    return when (tagBucket(f, tag)) {
        TagBucket.OFF -> stripped.copy(tagsAll = stripped.tagsAll + tag)
        TagBucket.ALL -> stripped.copy(tagsAny = stripped.tagsAny + tag)
        TagBucket.ANY -> stripped.copy(tagsNone = stripped.tagsNone + tag)
        TagBucket.NONE -> stripped
    }
}
