@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AutoAwesome
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
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.IsoDatePickerDialog
import dev.npn.app.ui.common.MAX_INLINE_SUGGESTIONS
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.TagEditor
import dev.npn.app.ui.common.TagSuggestionLabel
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.core.ai.AiField
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.StandardField
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_choose_date
import no_papers_needed.composeapp.generated.resources.detail_added
import no_papers_needed.composeapp.generated.resources.detail_correspondent
import no_papers_needed.composeapp.generated.resources.detail_dismiss_all
import no_papers_needed.composeapp.generated.resources.detail_dismiss_suggestion
import no_papers_needed.composeapp.generated.resources.detail_dismiss_tag
import no_papers_needed.composeapp.generated.resources.detail_document_date
import no_papers_needed.composeapp.generated.resources.detail_document_date_plain
import no_papers_needed.composeapp.generated.resources.detail_document_type
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable_hint
import no_papers_needed.composeapp.generated.resources.detail_file
import no_papers_needed.composeapp.generated.resources.detail_iso_date_template
import no_papers_needed.composeapp.generated.resources.detail_location
import no_papers_needed.composeapp.generated.resources.detail_mark_needs_review
import no_papers_needed.composeapp.generated.resources.detail_mark_reviewed
import no_papers_needed.composeapp.generated.resources.detail_note
import no_papers_needed.composeapp.generated.resources.detail_ocr
import no_papers_needed.composeapp.generated.resources.detail_pages
import no_papers_needed.composeapp.generated.resources.detail_suggest_metadata
import no_papers_needed.composeapp.generated.resources.detail_suggest_tags
import no_papers_needed.composeapp.generated.resources.detail_tab_details
import no_papers_needed.composeapp.generated.resources.detail_tab_history
import no_papers_needed.composeapp.generated.resources.detail_tab_preview
import no_papers_needed.composeapp.generated.resources.detail_tab_text
import no_papers_needed.composeapp.generated.resources.detail_tags
import no_papers_needed.composeapp.generated.resources.detail_title
import no_papers_needed.composeapp.generated.resources.detail_title_hint
import no_papers_needed.composeapp.generated.resources.detail_use_suggestion
import no_papers_needed.composeapp.generated.resources.ux_show_suggestions
import no_papers_needed.composeapp.generated.resources.ux_tags_saved
import org.jetbrains.compose.resources.stringResource

/**
 * Side panel for one document with three tabs: **Details** (editable metadata — title,
 * correspondent, docType, docDate, custom fields, tags, and file/OCR facts), **Text** (the
 * extracted OCR/embedded full text), and **History** (immutable journal timeline). Text fields
 * update the shared draft through [MetadataActions.edit]; correspondent/docType/field-keys offer
 * existing values as suggestions.
 *
 * Everything shown comes from [state] (assembled by `DocDetailViewModel.metadata`) and everything
 * it can do from [actions] (wired by [rememberMetadataActions]), so the two call sites — the
 * detail screen and the library's side panel — differ only in the last two parameters.
 *
 * When [previewTab] is provided (the library's side-panel preview), it is prepended as an extra
 * **Preview** tab and selected initially; the detail screen omits it since the page viewer sits
 * next to the panel there.
 */
@Composable
internal fun MetadataTabs(
    state: MetadataUiState,
    actions: MetadataActions,
    previewTab: (@Composable (Modifier) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // The document is gone — trashed or deleted on another replica while the panel was open, or
    // never loaded at all. Say so rather than leaving the surface empty; the detail screen catches
    // this first and shows its own scaffold (with a way back), so in practice this is the library's
    // side panel reacting to a document disappearing under it.
    val item = state.document.item
    if (item == null) {
        DocumentUnavailable(modifier)
        return
    }
    val tabs =
        remember(previewTab != null) {
            if (previewTab != null) PanelTab.entries.toList() else PanelTab.entries.filter { it != PanelTab.PREVIEW }
        }
    var tab by remember { mutableStateOf(tabs.first()) }
    LaunchedEffect(tab) {
        if (tab == PanelTab.HISTORY) {
            actions.history.onLoad()
        }
    }
    // Shared with OcrTextTab so Ctrl+F can move focus into its find field. Focus is only requested
    // when Ctrl+F raises the counter, never on a plain tab tap (which would pop the keyboard).
    val findFocus = remember { FocusRequester() }
    var findRequestCounter by remember { mutableStateOf(0) }
    Column(
        modifier.onKeyEvent { event ->
            // Ctrl+F jumps to the Text tab and focuses the find field (desktop).
            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.F) {
                tab = PanelTab.TEXT
                findRequestCounter++
                true
            } else {
                false
            }
        },
    ) {
        PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), edgePadding = 0.dp) {
            tabs.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { tab = t },
                    text = {
                        Text(
                            when (t) {
                                PanelTab.PREVIEW -> stringResource(Res.string.detail_tab_preview)
                                PanelTab.DETAILS -> stringResource(Res.string.detail_tab_details)
                                PanelTab.TEXT -> stringResource(Res.string.detail_tab_text)
                                PanelTab.HISTORY -> stringResource(Res.string.detail_tab_history)
                            },
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        when (tab) {
            PanelTab.PREVIEW -> {
                previewTab?.invoke(Modifier.fillMaxWidth().weight(1f))
            }

            PanelTab.DETAILS -> {
                DetailsTab(
                    state = state,
                    actions = actions,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            PanelTab.TEXT -> {
                OcrTextTab(
                    text = state.document.ocrText,
                    ocrState = item.doc.ocrState,
                    highlightTerms = state.document.highlightTerms,
                    focusRequester = findFocus,
                    focusRequest = findRequestCounter,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }

            PanelTab.HISTORY -> {
                HistoryTab(
                    history = state.history,
                    onRefresh = actions.history.onRefresh,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

@Composable
internal fun DocumentUnavailable(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth()) {
        EmptyState(
            icon = Icons.Filled.Delete,
            title = stringResource(Res.string.detail_document_unavailable),
            subtitle = stringResource(Res.string.detail_document_unavailable_hint),
        )
    }
}

private enum class PanelTab { PREVIEW, DETAILS, TEXT, HISTORY }

@Composable
private fun DetailsTab(
    state: MetadataUiState,
    actions: MetadataActions,
    modifier: Modifier = Modifier,
) {
    val item = state.document.item ?: return
    val doc = item.doc
    val vocabulary = state.vocabulary
    val edit = actions.edit
    val visibleFields = state.document.visibleFields

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReviewStatusSection(state.document.reviewMode, state.document.reviewed, edit.onSetReviewed)
        DocumentPropertiesSection(
            title = doc.title,
            fileName = doc.fileName,
            correspondent = doc.correspondent,
            docType = doc.docType,
            docDate = doc.docDate,
            correspondents = vocabulary.correspondents,
            docTypes = vocabulary.docTypes,
            onCommitTitle = edit.onCommitTitle,
            onCommitCorrespondent = edit.onCommitCorrespondent,
            onCommitDocType = edit.onCommitDocType,
            onCommitDocDate = edit.onCommitDocDate,
            visibleFields = visibleFields,
        )

        if (StandardField.NOTES in visibleFields) {
            NoteSection(doc.notes.orEmpty(), edit.onNoteChange)
        }
        AiMetadataSuggestions(
            available = state.ai.tags.available == true,
            state = state.ai.metadata,
            actions = actions.ai,
        )

        HorizontalDivider()

        CustomFieldsSection(
            // Until the registry-driven section lands, this list shows only the non-well-known keys;
            // the well-known five have their own controls above.
            fields = item.doc.fields.filterKeys { it !in FieldKeys.WELL_KNOWN },
            fieldKeys = vocabulary.fieldKeys,
            onCommitField = edit.onCommitField,
            onRemoveField = edit.onRemoveField,
        )

        HorizontalDivider()

        Text(
            stringResource(Res.string.detail_tags),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(Res.string.ux_tags_saved), style = MaterialTheme.typography.bodySmall)
        TagEditor(
            tags = item.tags,
            allTagNames = vocabulary.allTagNames,
            tagColors = vocabulary.tagColors,
            onAdd = actions.tags.onAdd,
            onRemove = actions.tags.onRemove,
            onAddWithStyle = actions.tags.onAddWithStyle,
        )

        AiTagSuggestions(
            state = state.ai.tags,
            actions = actions.ai,
        )

        HorizontalDivider()

        // File / OCR facts.
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FactRow(stringResource(Res.string.detail_file), doc.fileName)
            FactRow(stringResource(Res.string.detail_location), doc.relPath)
            FactRow(stringResource(Res.string.detail_added), formatEpochDate(doc.addedAt))
            doc.pageCount?.let { FactRow(stringResource(Res.string.detail_pages), it.toString()) }
            FactRow(stringResource(Res.string.detail_ocr), ocrLabel(doc.ocrState))
            OcrEngineRow(
                indexedEngine = doc.ocrEngine,
                pinned = doc.ocrEngineOverride != null,
                versions = state.document.ocrVersions,
                onSetEngine = edit.onSetOcrEngine,
            )
        }
    }
}

@Composable
private fun ReviewStatusSection(
    reviewMode: ReviewMode,
    reviewed: Boolean,
    onSetReviewed: (Boolean) -> Unit,
) {
    if (reviewMode != ReviewMode.INBOX_WORKFLOW) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReviewedBadge(reviewed)
        // Writes straight through (it also commits the draft), so it follows the save bar's rules.
        OutlinedButton(onClick = { onSetReviewed(!reviewed) }, enabled = !LocalDocumentWriteBlocked.current) {
            Text(
                stringResource(
                    if (reviewed) Res.string.detail_mark_needs_review else Res.string.detail_mark_reviewed,
                ),
            )
        }
    }
}

@Composable
private fun NoteSection(
    note: String,
    onNoteChange: (String) -> Unit,
) {
    // Matters most here: a note is prose, so spaces at the end of what is typed so far are
    // constant. See [rememberFieldText].
    var text by rememberFieldText(note)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onNoteChange(it)
        },
        label = { Text(stringResource(Res.string.detail_note)) },
        minLines = 4,
        maxLines = 10,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DocumentPropertiesSection(
    title: String?,
    fileName: String,
    correspondent: String?,
    docType: String?,
    docDate: String?,
    correspondents: List<String>,
    docTypes: List<String>,
    onCommitTitle: (String) -> Unit,
    onCommitCorrespondent: (String) -> Unit,
    onCommitDocType: (String) -> Unit,
    onCommitDocDate: (String) -> Unit,
    visibleFields: Set<StandardField> = StandardField.DEFAULT,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        CommitTextField(
            label = stringResource(Res.string.detail_title),
            initial = title.orEmpty(),
            placeholder = fileName,
            onCommit = onCommitTitle,
        )
        Text(
            stringResource(Res.string.detail_title_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (StandardField.CORRESPONDENT in visibleFields) {
        SuggestField(
            label = stringResource(Res.string.detail_correspondent),
            initial = correspondent.orEmpty(),
            suggestions = correspondents,
            onCommit = onCommitCorrespondent,
        )
    }
    if (StandardField.DOC_TYPE in visibleFields) {
        SuggestField(
            label = stringResource(Res.string.detail_document_type),
            initial = docType.orEmpty(),
            suggestions = docTypes,
            onCommit = onCommitDocType,
        )
    }
    if (StandardField.DOC_DATE in visibleFields) {
        DocumentDateField(
            docDate = docDate,
            onCommit = onCommitDocDate,
        )
    }
}

/** Date entry and calendar selection both update the same document draft. */
@Composable
private fun DocumentDateField(
    docDate: String?,
    onCommit: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommitTextField(
            label = stringResource(Res.string.detail_document_date_plain),
            initial = docDate.orEmpty(),
            placeholder = stringResource(Res.string.detail_iso_date_template),
            // Same rule the save bar enforces, so the field and the bar never disagree.
            isError = !isWritableDate(docDate),
            modifier = Modifier.weight(1f),
            onCommit = onCommit,
        )
        IconButton(onClick = { showPicker = true }) {
            Icon(
                Icons.Filled.CalendarToday,
                contentDescription = stringResource(Res.string.common_choose_date),
            )
        }
    }
    if (showPicker) {
        IsoDatePickerDialog(
            initial = docDate,
            title = stringResource(Res.string.detail_document_date),
            onSelect = { iso ->
                showPicker = false
                onCommit(iso.orEmpty())
            },
            onDismiss = { showPicker = false },
        )
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
    actions: AiActions,
) {
    if (state.available != true) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = actions.onSuggestTags, enabled = !state.suggesting) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(stringResource(Res.string.detail_suggest_tags), modifier = Modifier.padding(start = 8.dp))
            }
            if (state.suggesting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        if (state.suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.suggestions.forEach { suggestion ->
                    AiTagChip(suggestion, actions.onAcceptTag, actions.onDismissTag)
                }
            }
            TextButton(onClick = actions.onClearTagSuggestions) {
                Text(stringResource(Res.string.detail_dismiss_all))
            }
        }
    }
}

/**
 * One AI tag suggestion chip: tapping (or the leading +) accepts, the trailing × dismisses. New
 * (non-vocabulary) tags carry a small "new" badge after the label so the user can tell brand-new
 * tags from reused library tags before accepting.
 */
@Composable
private fun AiTagChip(
    suggestion: TagSuggestion,
    onAccept: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    val tag = suggestion.tag
    Row(verticalAlignment = Alignment.CenterVertically) {
        InputChip(
            modifier = Modifier.weight(1f, fill = false),
            selected = false,
            onClick = { onAccept(tag) },
            label = { TagSuggestionLabel(tag = tag, isNew = suggestion.isNew) },
            leadingIcon = { Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp)) },
        )
        IconButton(onClick = { onDismiss(tag) }) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.detail_dismiss_tag, tag))
        }
    }
}

/**
 * AI suggestions for the single-value properties (title/correspondent/date). Hidden entirely
 * unless the AI endpoint is configured ([available], shared with the tag probe). Offers a
 * "Suggest details" button (spinner while running) and renders each suggested field as a row:
 * the check accepts it through the normal commit path, the × dismisses it.
 *
 * Rows are driven by [AiField.entries], so a new suggestable field renders here as soon as the
 * enum gains it — the only thing to add is its label in [aiFieldLabel], which the compiler
 * demands.
 */
@Composable
private fun AiMetadataSuggestions(
    available: Boolean,
    state: AiMetadataState,
    actions: AiActions,
) {
    if (!available) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = actions.onSuggestMetadata, enabled = !state.suggesting) {
                Icon(
                    Icons.Outlined.AutoAwesome,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Text(stringResource(Res.string.detail_suggest_metadata), modifier = Modifier.padding(start = 8.dp))
            }
            if (state.suggesting) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            }
        }
        AiField.entries.forEach { field ->
            state.suggestion[field]?.let { value ->
                AiMetadataSuggestionRow(
                    label = aiFieldLabel(field),
                    value = value,
                    field = field,
                    onAccept = actions.onAcceptMetadata,
                    onDismiss = actions.onDismissMetadata,
                )
            }
        }
    }
}

/** Human label for a suggestable field; exhaustive, so a new [AiField] cannot ship unlabelled. */
@Composable
private fun aiFieldLabel(field: AiField): String =
    stringResource(
        when (field) {
            AiField.TITLE -> Res.string.detail_title
            AiField.CORRESPONDENT -> Res.string.detail_correspondent
            AiField.DOC_DATE -> Res.string.detail_document_date_plain
        },
    )

/** One suggested property value with accept (check) and dismiss (×) actions. */
@Composable
private fun AiMetadataSuggestionRow(
    label: String,
    value: String,
    field: AiField,
    onAccept: (AiField) -> Unit,
    onDismiss: (AiField) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        IconButton(onClick = { onAccept(field) }) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(Res.string.detail_use_suggestion, label),
            )
        }
        IconButton(onClick = { onDismiss(field) }) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(Res.string.detail_dismiss_suggestion, label),
            )
        }
    }
}

/**
 * The text a field is showing: its own keystrokes, seeded and re-seeded from [external].
 *
 * A field cannot render [external] directly. It arrives through the draft overlay, and the draft
 * store treats a value that only differs in surrounding whitespace as no change at all — so typing
 * a space at the end of a value hands back the value without it, and the space is swallowed as the
 * user types. Comparing trimmed forms makes that round trip a no-op here too, while an edit from
 * anywhere else — switching document, discarding the draft, accepting a suggestion, picking a date
 * — still replaces what is on screen.
 */
@Composable
private fun rememberFieldText(external: String): MutableState<String> {
    val text = remember { mutableStateOf(external) }
    if (external.trim() != text.value.trim()) text.value = external
    return text
}

/**
 * Text field that reports every keystroke through [onCommit], which the document editor turns into
 * a draft edit. The keystrokes themselves live in local state — see [rememberFieldText].
 */
@Composable
internal fun CommitTextField(
    label: String,
    initial: String,
    placeholder: String? = null,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    onCommit: (String) -> Unit,
) {
    var text by rememberFieldText(initial)
    OutlinedTextField(
        value = text,
        onValueChange = {
            text = it
            onCommit(it)
        },
        isError = isError,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, maxLines = 1) } },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun SuggestField(
    label: String,
    initial: String,
    suggestions: List<String>,
    onCommit: (String) -> Unit,
) {
    var text by rememberFieldText(initial)
    var expanded by remember { mutableStateOf(false) }
    val filtered = remember(text, suggestions) { suggestionsFor(text, suggestions) }
    androidx.compose.foundation.layout.Box {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                onCommit(it)
                // Match against the value just typed: [filtered] still describes the previous one.
                expanded = it.isNotBlank() && suggestionsFor(it, suggestions).isNotEmpty()
            },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = {
                if (suggestions.isNotEmpty()) {
                    IconButton(onClick = { expanded = !expanded }) {
                        Icon(
                            Icons.Filled.ArrowDropDown,
                            contentDescription = stringResource(Res.string.ux_show_suggestions),
                        )
                    }
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        DropdownMenu(
            expanded = expanded && filtered.isNotEmpty(),
            onDismissRequest = { expanded = false },
            properties =
                androidx.compose.ui.window
                    .PopupProperties(focusable = false),
        ) {
            filtered.forEach { s ->
                DropdownMenuItem(
                    text = { Text(s) },
                    onClick = {
                        expanded = false
                        text = s
                        onCommit(s)
                    },
                )
            }
        }
    }
}

/** Existing values worth offering for [text]; an exact match offers nothing (it is already typed). */
private fun suggestionsFor(
    text: String,
    suggestions: List<String>,
): List<String> {
    val query = text.trim()
    if (query.isEmpty()) return suggestions.take(MAX_INLINE_SUGGESTIONS)
    return suggestions.filter { it.contains(query, ignoreCase = true) && it != query }.take(MAX_INLINE_SUGGESTIONS)
}

@Composable
private fun FactRow(
    label: String,
    value: String,
) {
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
