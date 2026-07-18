@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.TagEditor
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.core.app.DocListItem
import dev.npn.core.model.ReviewMode

/**
 * Side panel for one document with two tabs: **Details** (editable metadata — title,
 * correspondent, docType, docDate, custom fields, tags, and file/OCR facts) and **Text** (the
 * extracted OCR/embedded full text). Text fields commit on focus loss via the [onCommit*]
 * callbacks; correspondent/docType/field-keys offer existing values as suggestions.
 */
@Composable
fun MetadataPanel(
    item: DocListItem,
    allTagNames: List<String>,
    tagColors: Map<String, String?>,
    correspondents: List<String>,
    docTypes: List<String>,
    fieldKeys: List<String>,
    ocrText: String?,
    onCommitTitle: (String) -> Unit,
    onCommitCorrespondent: (String) -> Unit,
    onCommitDocType: (String) -> Unit,
    onCommitDocDate: (String) -> Unit,
    onCommitField: (key: String, value: String) -> Unit,
    onRemoveField: (key: String) -> Unit,
    reviewMode: ReviewMode,
    reviewed: Boolean,
    onSetReviewed: (Boolean) -> Unit,
    onAddTag: (String) -> Unit,
    onRemoveTag: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(PanelTab.DETAILS) }
    Column(modifier) {
        TabRow(selectedTabIndex = tab.ordinal) {
            PanelTab.entries.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = { Text(t.label) },
                )
            }
        }
        when (tab) {
            PanelTab.DETAILS -> DetailsTab(
                item = item,
                allTagNames = allTagNames,
                tagColors = tagColors,
                correspondents = correspondents,
                docTypes = docTypes,
                fieldKeys = fieldKeys,
                onCommitTitle = onCommitTitle,
                onCommitCorrespondent = onCommitCorrespondent,
                onCommitDocType = onCommitDocType,
                onCommitDocDate = onCommitDocDate,
                onCommitField = onCommitField,
                onRemoveField = onRemoveField,
                reviewMode = reviewMode,
                reviewed = reviewed,
                onSetReviewed = onSetReviewed,
                onAddTag = onAddTag,
                onRemoveTag = onRemoveTag,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            PanelTab.TEXT -> OcrTextTab(
                text = ocrText,
                ocrState = item.doc.ocrState,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

private enum class PanelTab(val label: String) {
    DETAILS("Details"),
    TEXT("Text"),
}

@Composable
private fun DetailsTab(
    item: DocListItem,
    allTagNames: List<String>,
    tagColors: Map<String, String?>,
    correspondents: List<String>,
    docTypes: List<String>,
    fieldKeys: List<String>,
    onCommitTitle: (String) -> Unit,
    onCommitCorrespondent: (String) -> Unit,
    onCommitDocType: (String) -> Unit,
    onCommitDocDate: (String) -> Unit,
    onCommitField: (key: String, value: String) -> Unit,
    onRemoveField: (key: String) -> Unit,
    reviewMode: ReviewMode,
    reviewed: Boolean,
    onSetReviewed: (Boolean) -> Unit,
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
        // Reviewed row. Only the inbox workflow has a review status; without it there is no
        // badge and no manual toggle (reviewed state is derived from tags).
        if (reviewMode == ReviewMode.INBOX_WORKFLOW) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ReviewedBadge(reviewed)
                OutlinedButton(onClick = { onSetReviewed(!reviewed) }) {
                    Text(if (reviewed) "Mark needs review" else "Mark reviewed")
                }
            }
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

        CustomFieldsSection(
            fields = item.fields,
            fieldKeys = fieldKeys,
            onCommitField = onCommitField,
            onRemoveField = onRemoveField,
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

/** Text field that commits its value when the user leaves the field (focus lost) or edits stop. */
@Composable
private fun CommitTextField(
    label: String,
    initial: String,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    onCommit: (String) -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        singleLine = true,
        modifier = modifier
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

/**
 * Custom metadata fields: existing fields render as commit-on-blur text fields (blanking one
 * removes it), plus an add-field row with a key box (autocompleting from [fieldKeys] already used
 * in the library) and a value box.
 */
@Composable
private fun CustomFieldsSection(
    fields: Map<String, String>,
    fieldKeys: List<String>,
    onCommitField: (key: String, value: String) -> Unit,
    onRemoveField: (key: String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Fields",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        // Deterministic order so fields don't jump around as the map re-emits.
        for (fieldKey in fields.keys.sorted()) {
            // key() ties each row's composition state (edit buffer, focus) to the field key
            // instead of its list position. Without it, adding/removing a field shifts rows into
            // other keys' slots; the reused CommitTextField then holds a stale edit buffer and its
            // recomposition-induced blur re-commits the old value, silently undoing a just-issued
            // delete (LWW: the spurious write is newer than the delete).
            key(fieldKey) {
                val value = fields.getValue(fieldKey)
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CommitTextField(
                        label = fieldKey,
                        initial = value,
                        onCommit = { onCommitField(fieldKey, it) },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { onRemoveField(fieldKey) }) {
                        Icon(Icons.Filled.Close, contentDescription = "Remove field $fieldKey")
                    }
                }
            }
        }
        AddFieldRow(existingKeys = fields.keys, fieldKeys = fieldKeys, onAdd = onCommitField)
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
    // Suggest keys used elsewhere in the library that this doc doesn't already have.
    val suggestions = remember(key, fieldKeys, existingKeys) {
        val q = key.trim()
        fieldKeys.filter { it !in existingKeys && (q.isEmpty() || it.contains(q, ignoreCase = true)) }
            .take(8)
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
        androidx.compose.foundation.layout.Box(Modifier.weight(1f)) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it; expanded = it.isNotBlank() && suggestions.isNotEmpty() },
                label = { Text("New field") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(
                expanded = expanded && suggestions.isNotEmpty(),
                onDismissRequest = { expanded = false },
                properties = androidx.compose.ui.window.PopupProperties(focusable = false),
            ) {
                suggestions.forEach { s ->
                    DropdownMenuItem(text = { Text(s) }, onClick = { key = s; expanded = false })
                }
            }
        }
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("Value") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { commit() }, enabled = canAdd) {
            Icon(Icons.Filled.Add, contentDescription = "Add field")
        }
    }
}

/** Read-only extracted text (OCR / embedded), selectable, in a scrolling monospace-ish block. */
@Composable
private fun OcrTextTab(text: String?, ocrState: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        when {
            text != null -> SelectionContainer {
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
            else -> Text(
                when (ocrState) {
                    "pending" -> "Text extraction is queued for this document."
                    "failed" -> "Text extraction failed for this document."
                    "none" -> "No text has been extracted from this document yet."
                    else -> "No text available."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
