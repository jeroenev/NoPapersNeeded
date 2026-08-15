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
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Tab
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HighlightText
import dev.npn.app.ui.common.IsoDatePickerDialog
import dev.npn.app.ui.common.MAX_INLINE_SUGGESTIONS
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.TagEditor
import dev.npn.app.ui.common.TagSuggestionLabel
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.app.ui.common.formatEpochDateTime
import dev.npn.app.ui.common.parseFtsSnippet
import dev.npn.app.ui.common.parseQueryHighlight
import dev.npn.core.ai.AiField
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.DocListItem
import dev.npn.core.app.OcrVersion
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.StandardField
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_apply
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_choose_date
import no_papers_needed.composeapp.generated.resources.common_retry
import no_papers_needed.composeapp.generated.resources.common_unknown_error
import no_papers_needed.composeapp.generated.resources.detail_add_field
import no_papers_needed.composeapp.generated.resources.detail_add_tag
import no_papers_needed.composeapp.generated.resources.detail_added
import no_papers_needed.composeapp.generated.resources.detail_automatic_engine
import no_papers_needed.composeapp.generated.resources.detail_choose_ocr_engine
import no_papers_needed.composeapp.generated.resources.detail_correspondent
import no_papers_needed.composeapp.generated.resources.detail_dismiss_all
import no_papers_needed.composeapp.generated.resources.detail_dismiss_suggestion
import no_papers_needed.composeapp.generated.resources.detail_dismiss_tag
import no_papers_needed.composeapp.generated.resources.detail_document_date
import no_papers_needed.composeapp.generated.resources.detail_document_date_plain
import no_papers_needed.composeapp.generated.resources.detail_document_type
import no_papers_needed.composeapp.generated.resources.detail_engine
import no_papers_needed.composeapp.generated.resources.detail_engine_ai
import no_papers_needed.composeapp.generated.resources.detail_engine_embedded
import no_papers_needed.composeapp.generated.resources.detail_engine_mlkit
import no_papers_needed.composeapp.generated.resources.detail_engine_source
import no_papers_needed.composeapp.generated.resources.detail_engine_tesseract
import no_papers_needed.composeapp.generated.resources.detail_fields
import no_papers_needed.composeapp.generated.resources.detail_file
import no_papers_needed.composeapp.generated.resources.detail_history_load_failed
import no_papers_needed.composeapp.generated.resources.detail_history_secondary
import no_papers_needed.composeapp.generated.resources.detail_iso_date_template
import no_papers_needed.composeapp.generated.resources.detail_location
import no_papers_needed.composeapp.generated.resources.detail_mark_needs_review
import no_papers_needed.composeapp.generated.resources.detail_mark_reviewed
import no_papers_needed.composeapp.generated.resources.detail_new_field
import no_papers_needed.composeapp.generated.resources.detail_no_history
import no_papers_needed.composeapp.generated.resources.detail_not_available
import no_papers_needed.composeapp.generated.resources.detail_note
import no_papers_needed.composeapp.generated.resources.detail_note_save_failed
import no_papers_needed.composeapp.generated.resources.detail_ocr
import no_papers_needed.composeapp.generated.resources.detail_ocr_adopted
import no_papers_needed.composeapp.generated.resources.detail_ocr_done
import no_papers_needed.composeapp.generated.resources.detail_ocr_failed
import no_papers_needed.composeapp.generated.resources.detail_ocr_not_processed
import no_papers_needed.composeapp.generated.resources.detail_ocr_queued
import no_papers_needed.composeapp.generated.resources.detail_pages
import no_papers_needed.composeapp.generated.resources.detail_pinned_engine
import no_papers_needed.composeapp.generated.resources.detail_remove_field
import no_papers_needed.composeapp.generated.resources.detail_save_note
import no_papers_needed.composeapp.generated.resources.detail_saved
import no_papers_needed.composeapp.generated.resources.detail_saving
import no_papers_needed.composeapp.generated.resources.detail_search_text_engine
import no_papers_needed.composeapp.generated.resources.detail_suggest_metadata
import no_papers_needed.composeapp.generated.resources.detail_suggest_tags
import no_papers_needed.composeapp.generated.resources.detail_tab_details
import no_papers_needed.composeapp.generated.resources.detail_tab_history
import no_papers_needed.composeapp.generated.resources.detail_tab_preview
import no_papers_needed.composeapp.generated.resources.detail_tab_text
import no_papers_needed.composeapp.generated.resources.detail_tags
import no_papers_needed.composeapp.generated.resources.detail_text_failed
import no_papers_needed.composeapp.generated.resources.detail_text_not_extracted
import no_papers_needed.composeapp.generated.resources.detail_text_queued
import no_papers_needed.composeapp.generated.resources.detail_text_unavailable
import no_papers_needed.composeapp.generated.resources.detail_title
import no_papers_needed.composeapp.generated.resources.detail_title_hint
import no_papers_needed.composeapp.generated.resources.detail_unsaved_changes
import no_papers_needed.composeapp.generated.resources.detail_use_suggestion
import no_papers_needed.composeapp.generated.resources.detail_value
import org.jetbrains.compose.resources.stringResource

/**
 * Side panel for one document with three tabs: **Details** (editable metadata — title,
 * correspondent, docType, docDate, custom fields, tags, and file/OCR facts), **Text** (the
 * extracted OCR/embedded full text), and **History** (immutable journal timeline). Text fields
 * commit on focus loss through [MetadataActions.edit]; correspondent/docType/field-keys offer
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
fun MetadataPanel(
    state: MetadataUiState,
    actions: MetadataActions,
    previewTab: (@Composable (Modifier) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // No document row yet (or the document is gone): the panel has nothing to render, and the
    // screens above own the "unavailable" messaging.
    val item = state.document.item ?: return
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
        PrimaryTabRow(selectedTabIndex = tabs.indexOf(tab)) {
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
        if (StandardField.NOTES in visibleFields) {
            NoteSection(state.note, edit.onNoteChange, edit.onSaveNote)
        }
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

        AiMetadataSuggestions(
            available = state.ai.tags.available == true,
            state = state.ai.metadata,
            actions = actions.ai,
        )

        HorizontalDivider()

        CustomFieldsSection(
            fields = item.fields,
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
        OutlinedButton(onClick = { onSetReviewed(!reviewed) }) {
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
    noteEditor: NoteEditorState,
    onNoteChange: (String) -> Unit,
    onSaveNote: () -> Unit,
) {
    OutlinedTextField(
        value = noteEditor.draft,
        onValueChange = onNoteChange,
        label = { Text(stringResource(Res.string.detail_note)) },
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
            noteSaveLabel(noteEditor),
            style = MaterialTheme.typography.bodySmall,
            color =
                if (noteEditor.saveState is NoteSaveState.Error) {
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
                Text(stringResource(Res.string.detail_save_note))
            }
        }
    }
}

@Composable
private fun noteSaveLabel(noteEditor: NoteEditorState): String =
    when (val state = noteEditor.saveState) {
        NoteSaveState.Idle -> {
            if (noteEditor.dirty) stringResource(Res.string.detail_unsaved_changes) else ""
        }

        NoteSaveState.Saving -> {
            stringResource(Res.string.detail_saving)
        }

        NoteSaveState.Saved -> {
            stringResource(Res.string.detail_saved)
        }

        is NoteSaveState.Error -> {
            stringResource(
                Res.string.detail_note_save_failed,
                state.message.ifBlank { stringResource(Res.string.common_unknown_error) },
            )
        }
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

/**
 * Document-date field: manual ISO entry via [CommitTextField] plus a trailing calendar action that
 * opens an [IsoDatePickerDialog]. Picking commits immediately and also seeds the field's text (keyed
 * on [docDate] so it resets when the doc changes) so the blur-commit never fires with stale text.
 */
@Composable
private fun DocumentDateField(
    docDate: String?,
    onCommit: (String) -> Unit,
) {
    var showPicker by remember { mutableStateOf(false) }
    var picked by remember(docDate) { mutableStateOf<String?>(null) }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommitTextField(
            label = stringResource(Res.string.detail_document_date),
            initial = picked ?: docDate.orEmpty(),
            placeholder = stringResource(Res.string.detail_iso_date_template),
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
                picked = iso.orEmpty()
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
    InputChip(
        selected = false,
        onClick = { onAccept(tag) },
        label = { TagSuggestionLabel(tag = tag, isNew = suggestion.isNew) },
        leadingIcon = {
            Icon(
                Icons.Filled.Add,
                contentDescription = stringResource(Res.string.detail_add_tag, tag),
                modifier = Modifier.size(AssistChipDefaults.IconSize),
            )
        },
        trailingIcon = {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(Res.string.detail_dismiss_tag, tag),
                modifier =
                    Modifier
                        .size(AssistChipDefaults.IconSize)
                        .clickable { onDismiss(tag) },
            )
        },
    )
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

/** Text field that commits its value when the user leaves the field (focus lost) or edits stop. */
@Composable
internal fun CommitTextField(
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
        modifier =
            modifier
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
): Modifier =
    composed {
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
    val filtered =
        remember(text, suggestions) {
            val q = text.trim()
            if (q.isEmpty()) {
                suggestions.take(MAX_INLINE_SUGGESTIONS)
            } else {
                suggestions.filter { it.contains(q, ignoreCase = true) && it != q }.take(MAX_INLINE_SUGGESTIONS)
            }
        }
    androidx.compose.foundation.layout.Box {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                expanded = it.isNotBlank() && filtered.isNotEmpty()
            },
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .commitOnFocusLost(text, initial, onCommit),
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
