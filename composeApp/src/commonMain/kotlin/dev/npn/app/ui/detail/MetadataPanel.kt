@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Help
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.LabelOff
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Title
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import dev.npn.app.ui.common.HighlightText
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.TagEditor
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.app.ui.common.formatEpochDateTime
import dev.npn.app.ui.common.parseFtsSnippet
import dev.npn.app.ui.common.parseQueryHighlight
import dev.npn.core.app.DocListItem
import dev.npn.core.app.OcrVersion
import dev.npn.core.model.ReviewMode

/**
 * Side panel for one document with three tabs: **Details** (editable metadata — title,
 * correspondent, docType, docDate, custom fields, tags, and file/OCR facts), **Text** (the
 * extracted OCR/embedded full text), and **History** (immutable journal timeline). Text fields
 * commit on focus loss via the [onCommit*] callbacks; correspondent/docType/field-keys offer
 * existing values as suggestions.
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
    ocrVersions: List<OcrVersion>,
    highlightTerms: List<String> = emptyList(),
    onSetOcrEngine: (String?) -> Unit,
    onCommitTitle: (String) -> Unit,
    noteEditor: NoteEditorState,
    onNoteChange: (String) -> Unit,
    onSaveNote: () -> Unit,
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
    aiTags: AiTagState = AiTagState(),
    onSuggestAiTags: () -> Unit = {},
    onAcceptAiTag: (String) -> Unit = {},
    onDismissAiTag: (String) -> Unit = {},
    onClearAiTagSuggestions: () -> Unit = {},
    history: HistoryUiState = HistoryUiState.NotLoaded,
    onLoadHistory: () -> Unit = {},
    onRefreshHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var tab by remember { mutableStateOf(PanelTab.DETAILS) }
    LaunchedEffect(tab) {
        if (tab == PanelTab.HISTORY) {
            onLoadHistory()
        }
    }
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
                ocrVersions = ocrVersions,
                onSetOcrEngine = onSetOcrEngine,
                onCommitTitle = onCommitTitle,
                noteEditor = noteEditor,
                onNoteChange = onNoteChange,
                onSaveNote = onSaveNote,
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
                aiTags = aiTags,
                onSuggestAiTags = onSuggestAiTags,
                onAcceptAiTag = onAcceptAiTag,
                onDismissAiTag = onDismissAiTag,
                onClearAiTagSuggestions = onClearAiTagSuggestions,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            PanelTab.TEXT -> OcrTextTab(
                text = ocrText,
                ocrState = item.doc.ocrState,
                highlightTerms = highlightTerms,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
            PanelTab.HISTORY -> HistoryTab(
                history = history,
                onRefresh = onRefreshHistory,
                modifier = Modifier.fillMaxWidth().weight(1f),
            )
        }
    }
}

private enum class PanelTab(val label: String) {
    DETAILS("Details"),
    TEXT("Text"),
    HISTORY("History"),
}

@Composable
@Suppress("LongMethod")
private fun DetailsTab(
    item: DocListItem,
    allTagNames: List<String>,
    tagColors: Map<String, String?>,
    correspondents: List<String>,
    docTypes: List<String>,
    fieldKeys: List<String>,
    ocrVersions: List<OcrVersion>,
    onSetOcrEngine: (String?) -> Unit,
    onCommitTitle: (String) -> Unit,
    noteEditor: NoteEditorState,
    onNoteChange: (String) -> Unit,
    onSaveNote: () -> Unit,
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
    aiTags: AiTagState,
    onSuggestAiTags: () -> Unit,
    onAcceptAiTag: (String) -> Unit,
    onDismissAiTag: (String) -> Unit,
    onClearAiTagSuggestions: () -> Unit,
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

        OutlinedTextField(
            value = noteEditor.draft,
            onValueChange = onNoteChange,
            label = { Text("Note") },
            minLines = 4,
            maxLines = 10,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                when (val state = noteEditor.saveState) {
                    NoteSaveState.Idle -> if (noteEditor.dirty) "Unsaved changes" else ""
                    NoteSaveState.Saving -> "Saving..."
                    NoteSaveState.Saved -> "Saved"
                    is NoteSaveState.Error -> state.message
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (noteEditor.saveState is NoteSaveState.Error) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Button(
                onClick = onSaveNote,
                enabled = noteEditor.dirty && noteEditor.saveState != NoteSaveState.Saving,
            ) {
                if (noteEditor.saveState == NoteSaveState.Saving) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text("Save note")
                }
            }
        }

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

        AiTagSuggestions(
            state = aiTags,
            onSuggest = onSuggestAiTags,
            onAccept = onAcceptAiTag,
            onDismiss = onDismissAiTag,
            onClearAll = onClearAiTagSuggestions,
        )

        HorizontalDivider()

        // File / OCR facts.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FactRow("File", doc.fileName)
            FactRow("Location", doc.relPath)
            FactRow("Added", formatEpochDate(doc.addedAt))
            doc.pageCount?.let { FactRow("Pages", it.toString()) }
            FactRow("OCR", ocrLabel(doc.ocrState))
            OcrEngineRow(
                indexedEngine = doc.ocrEngine,
                pinned = doc.ocrEngineOverride != null,
                versions = ocrVersions,
                onSetEngine = onSetOcrEngine,
            )
        }
    }
}

/**
 * AI new-tag suggestions for the current doc. Hidden entirely unless the AI endpoint is configured
 * ([AiTagState.available]). Offers a "Suggest tags" button (spinner while running) and renders each
 * suggestion as an [InputChip] visually distinct from real tags: tapping the chip (or its leading +)
 * accepts it, the trailing × dismisses it; a "Dismiss all" button clears the lot.
 */
@Composable
private fun AiTagSuggestions(
    state: AiTagState,
    onSuggest: () -> Unit,
    onAccept: (String) -> Unit,
    onDismiss: (String) -> Unit,
    onClearAll: () -> Unit,
) {
    if (state.available != true) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onSuggest, enabled = !state.suggesting) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text("Suggest tags", modifier = Modifier.padding(start = 8.dp))
            }
            if (state.suggesting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        if (state.suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.suggestions.forEach { tag ->
                    InputChip(
                        selected = false,
                        onClick = { onAccept(tag) },
                        label = { Text(tag) },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Add,
                                contentDescription = "Add $tag",
                                modifier = Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                        trailingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Dismiss $tag",
                                modifier = Modifier
                                    .size(AssistChipDefaults.IconSize)
                                    .clickable { onDismiss(tag) },
                            )
                        },
                    )
                }
            }
            TextButton(onClick = onClearAll) { Text("Dismiss all") }
        }
    }
}

/**
 * Shows the OCR engine whose version is currently indexed for search, with a pin indicator when
 * the user has overridden automatic selection. Tapping it opens [OcrEnginePickerDialog] when there
 * are stored versions to choose from.
 */
@Composable
private fun OcrEngineRow(
    indexedEngine: String?,
    pinned: Boolean,
    versions: List<OcrVersion>,
    onSetEngine: (String?) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    val hasVersions = versions.isNotEmpty()
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (hasVersions) Modifier.clickable { showPicker = true } else Modifier),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Engine",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 4.dp),
        )
        Text(indexedEngine?.let { engineLabel(it) } ?: "—", style = MaterialTheme.typography.bodySmall)
        if (pinned) {
            Icon(
                Icons.Filled.PushPin,
                contentDescription = "Pinned engine",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp),
            )
        }
        if (hasVersions) {
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = "Choose OCR engine",
                modifier = Modifier.size(20.dp),
            )
        }
    }
    if (showPicker) {
        OcrEnginePickerDialog(
            versions = versions,
            pinnedEngine = if (pinned) indexedEngine else null,
            onConfirm = {
                onSetEngine(it)
                showPicker = false
            },
            onDismiss = { showPicker = false },
        )
    }
}

/**
 * Radio picker for the searchable OCR engine: "Automatic" plus one entry per distinct stored
 * engine (labeled with its display name and the source device + date). Selecting "Automatic"
 * clears the pin; any other choice pins that engine.
 */
@Composable
private fun OcrEnginePickerDialog(
    versions: List<OcrVersion>,
    pinnedEngine: String?,
    onConfirm: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val engines = remember(versions) { versions.distinctBy { it.engine } }
    var selected by remember { mutableStateOf(pinnedEngine) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Search text engine") },
        text = {
            Column {
                OcrEngineOption(
                    label = "Automatic (best available)",
                    secondary = null,
                    selected = selected == null,
                    onClick = { selected = null },
                )
                engines.forEach { version ->
                    OcrEngineOption(
                        label = engineLabel(version.engine),
                        secondary = "${version.deviceId} · ${formatEpochDate(version.createdAt)}",
                        selected = selected == version.engine,
                        onClick = { selected = version.engine },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun OcrEngineOption(
    label: String,
    secondary: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Column {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            if (secondary != null) {
                Text(
                    secondary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun OcrTextTab(
    text: String?,
    ocrState: String,
    highlightTerms: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        when {
            text != null -> SelectionContainer {
                val visibleText = text.take(MAX_OCR_DISPLAY_CHARS)
                if (highlightTerms.isNotEmpty()) {
                    HighlightText(
                        segments = parseQueryHighlight(visibleText, highlightTerms.joinToString(" ")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Text(visibleText, style = MaterialTheme.typography.bodySmall)
                }
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

private const val MAX_OCR_DISPLAY_CHARS = 200_000

/** Immutable journal timeline for the document: HLC-ordered icon + description rows. */
@Composable
private fun HistoryTab(
    history: HistoryUiState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    Column(
        modifier = modifier
            .verticalScroll(scroll)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (history) {
            HistoryUiState.NotLoaded, HistoryUiState.Loading -> CircularProgressIndicator()
            HistoryUiState.Empty -> Text(
                "No history events yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            is HistoryUiState.Error -> {
                Text("History could not be loaded: ${history.message}")
                Button(onClick = onRefresh) { Text("Retry") }
            }
            is HistoryUiState.Loaded -> for (item in history.items) {
                HistoryRow(item = item)
            }
        }
    }
}

@Composable
private fun HistoryRow(item: HistoryItem) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = item.kind.icon(),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(item.description, style = MaterialTheme.typography.bodyMedium)
            Text(
                formatHistorySecondary(item),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatHistorySecondary(item: HistoryItem): String {
    val timestamp = formatEpochDateTime(item.hlc.physicalMillis)
    return "$timestamp · Device ${item.deviceId}"
}

private fun HistoryEventKind.icon() = when (this) {
    HistoryEventKind.TAG_ADD -> Icons.Filled.Label
    HistoryEventKind.TAG_REMOVE -> Icons.Filled.LabelOff
    HistoryEventKind.PROP_TITLE -> Icons.Filled.Title
    HistoryEventKind.PROP_NOTES -> Icons.Filled.Description
    HistoryEventKind.PROP_CORRESPONDENT -> Icons.Filled.Person
    HistoryEventKind.PROP_DOC_TYPE -> Icons.Filled.Description
    HistoryEventKind.PROP_DOC_DATE -> Icons.Filled.CalendarToday
    HistoryEventKind.PROP_REVIEWED -> Icons.Filled.CheckCircle
    HistoryEventKind.PROP_OCR_ENGINE -> Icons.Filled.Settings
    HistoryEventKind.FIELD_SET -> Icons.Filled.TextFields
    HistoryEventKind.FIELD_REMOVE -> Icons.Filled.TextFields
    HistoryEventKind.TRASH -> Icons.Filled.Delete
    HistoryEventKind.RESTORE -> Icons.Filled.RestoreFromTrash
    HistoryEventKind.DELETE -> Icons.Filled.DeleteForever
    HistoryEventKind.RELINK -> Icons.Filled.Link
    HistoryEventKind.UNKNOWN -> Icons.Filled.Help
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

/** Display name for an OCR engine identifier. */
private fun engineLabel(engine: String): String = when (engine) {
    "tesseract" -> "Tesseract"
    "mlkit" -> "MLKit"
    "ai" -> "AI"
    "embedded" -> "Embedded text"
    else -> engine
}
