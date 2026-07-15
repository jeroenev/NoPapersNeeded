@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.StatusBadge
import dev.npn.app.ui.common.TagEditor
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.app.ui.common.statusLabel
import dev.npn.core.app.DocListItem
import dev.npn.core.model.DocStatus

/**
 * Editable metadata for one document: title, correspondent, docType, docDate, notes-style fields,
 * tag editor, and OCR/extraction status. Text fields commit on focus loss / explicit save via the
 * [onCommit*] callbacks. Correspondent/docType offer existing values as suggestions.
 */
@Composable
fun MetadataPanel(
    item: DocListItem,
    allTagNames: List<String>,
    tagColors: Map<String, String?>,
    correspondents: List<String>,
    docTypes: List<String>,
    onCommitTitle: (String) -> Unit,
    onCommitCorrespondent: (String) -> Unit,
    onCommitDocType: (String) -> Unit,
    onCommitDocDate: (String) -> Unit,
    onSetStatus: (DocStatus) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val doc = item.doc

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Status row.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusBadge(DocStatus.fromWire(doc.status))
            StatusDropdown(current = DocStatus.fromWire(doc.status), onSelect = onSetStatus)
        }

        CommitTextField(
            label = "Title",
            initial = doc.title ?: "",
            placeholder = doc.fileName,
            onCommit = onCommitTitle,
        )

        SuggestField(
            label = "Correspondent",
            initial = doc.correspondent ?: "",
            suggestions = correspondents,
            onCommit = onCommitCorrespondent,
        )

        SuggestField(
            label = "Document type",
            initial = doc.docType ?: "",
            suggestions = docTypes,
            onCommit = onCommitDocType,
        )

        CommitTextField(
            label = "Document date (yyyy-MM-dd)",
            initial = doc.docDate ?: "",
            placeholder = "yyyy-MM-dd",
            onCommit = onCommitDocDate,
        )

        HorizontalDivider()

        Text("Tags", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        TagEditor(
            tags = item.tags,
            allTagNames = allTagNames,
            tagColors = tagColors,
            onAdd = onAddTag,
            onRemove = onRemoveTag,
        )

        HorizontalDivider()

        // File / OCR facts.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FactRow("File", doc.fileName)
            FactRow("Location", doc.relPath)
            FactRow("Added", formatEpochDate(doc.addedAt))
            doc.pageCount?.let { FactRow("Pages", it.toString()) }
            FactRow("OCR", ocrLabel(doc.ocrState))
        }
    }
}

@Composable
private fun StatusDropdown(current: DocStatus, onSelect: (DocStatus) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(statusLabel(current))
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DocStatus.entries.forEach { s ->
                DropdownMenuItem(
                    text = { Text(statusLabel(s)) },
                    onClick = {
                        onSelect(s)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Text field that commits its value when the user leaves the field (focus lost) or edits stop. */
@Composable
private fun CommitTextField(
    label: String,
    initial: String,
    placeholder: String? = null,
    onCommit: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        singleLine = true,
        modifier = Modifier
            .fillMaxWidth()
            .commitOnFocusLost(text, initial, onCommit),
    )
}

/**
 * Fires [onCommit] with the current [value] when the field loses focus and the value changed from
 * [initial]. Keeps edits local while typing and writes a single journal event on blur.
 */
private fun Modifier.commitOnFocusLost(
    value: String,
    initial: String,
    onCommit: (String) -> Unit,
): Modifier = composed {
    var wasFocused by remember { mutableStateOf(false) }
    onFocusChanged { fs ->
        if (wasFocused && !fs.isFocused) {
            if (value.trim() != initial.trim()) onCommit(value.trim())
        }
        wasFocused = fs.isFocused
    }
}

@Composable
private fun SuggestField(
    label: String,
    initial: String,
    suggestions: List<String>,
    onCommit: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(text, suggestions) {
        val q = text.trim()
        if (q.isEmpty()) suggestions.take(8)
        else suggestions.filter { it.contains(q, ignoreCase = true) && it != q }.take(8)
    }
    androidx.compose.foundation.layout.Box {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it; expanded = it.isNotBlank() && filtered.isNotEmpty() },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    Icon(
                        Icons.Filled.ArrowDropDown,
                        contentDescription = null,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .commitOnFocusLost(text, initial, onCommit),
        )
        DropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
            properties = androidx.compose.ui.window.PopupProperties(focusable = false),
        ) {
            filtered.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s) },
                    onClick = {
                        text = s
                        expanded = false
                        onCommit(s)
                    },
                )
            }
        }
    }
}

@Composable
private fun FactRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun ocrLabel(state: String): String = when (state) {
    "none" -> "Not processed"
    "pending" -> "Queued"
    "done" -> "Done"
    "failed" -> "Failed"
    "adopted" -> "Adopted from another device"
    else -> state
}
