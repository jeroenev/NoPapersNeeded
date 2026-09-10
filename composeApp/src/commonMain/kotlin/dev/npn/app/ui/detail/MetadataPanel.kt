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
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.IsoDatePickerDialog
import dev.npn.app.ui.common.MAX_INLINE_SUGGESTIONS
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.TagEditor
import dev.npn.app.ui.common.TagSuggestionLabel
import dev.npn.app.ui.common.enumNameSaver
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_choose_date
import no_papers_needed.composeapp.generated.resources.detail_added
import no_papers_needed.composeapp.generated.resources.detail_dismiss_all
import no_papers_needed.composeapp.generated.resources.detail_dismiss_suggestion
import no_papers_needed.composeapp.generated.resources.detail_dismiss_tag
import no_papers_needed.composeapp.generated.resources.detail_document_date
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
import no_papers_needed.composeapp.generated.resources.detail_suggesting_metadata
import no_papers_needed.composeapp.generated.resources.detail_suggesting_tags
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
 * Side panel for one document with three tabs: **Details** (editable metadata — the registry's
 * fields, tags, and file/OCR facts), **Text** (the extracted OCR/embedded full text), and
 * **History** (immutable journal timeline). Field editors update the shared draft through
 * [MetadataActions.edit]; text fields offer existing values as suggestions.
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
    var savedTab by rememberSaveable(stateSaver = enumNameSaver()) { mutableStateOf(tabs.first()) }
    val tab = savedTab.takeIf { it in tabs } ?: tabs.first()
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
                savedTab = PanelTab.TEXT
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
                    onClick = { savedTab = t },
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

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReviewStatusSection(state.document.reviewMode, state.document.reviewed, edit.onSetReviewed)
        FieldsSection(
            doc = doc,
            defs = state.document.fieldDefs,
            vocabulary = vocabulary,
            edit = edit,
        )
        AiMetadataSuggestions(
            available = state.ai.tags.available == true,
            state = state.ai.metadata,
            labels = state.document.fieldDefs.associate { it.key to it.label },
            actions = actions.ai,
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
                val busyLabel = stringResource(Res.string.detail_suggesting_tags)
                CircularProgressIndicator(
                    Modifier.size(16.dp).semantics {
                        contentDescription = busyLabel
                        liveRegion = LiveRegionMode.Polite
                    },
                    strokeWidth = 2.dp,
                )
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
 * AI suggestions for the document's fields. Hidden entirely unless the AI endpoint is configured
 * ([available], shared with the tag probe). Offers a "Suggest details" button (spinner while
 * running) and renders each suggested field as a row labelled from the registry: the check
 * accepts it through the normal commit path, the × dismisses it.
 */
@Composable
private fun AiMetadataSuggestions(
    available: Boolean,
    state: AiMetadataState,
    labels: Map<String, String>,
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
                val busyLabel = stringResource(Res.string.detail_suggesting_metadata)
                CircularProgressIndicator(
                    Modifier.size(16.dp).semantics {
                        contentDescription = busyLabel
                        liveRegion = LiveRegionMode.Polite
                    },
                    strokeWidth = 2.dp,
                )
            }
        }
        for ((key, value) in state.suggestion.values) {
            AiMetadataSuggestionRow(
                label = labels[key] ?: key,
                value = value,
                key = key,
                onAccept = actions.onAcceptMetadata,
                onDismiss = actions.onDismissMetadata,
            )
        }
    }
}

/** One suggested field value with accept (check) and dismiss (×) actions. */
@Composable
private fun AiMetadataSuggestionRow(
    label: String,
    value: String,
    key: String,
    onAccept: (String) -> Unit,
    onDismiss: (String) -> Unit,
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
        IconButton(onClick = { onAccept(key) }) {
            Icon(
                Icons.Filled.Check,
                contentDescription = stringResource(Res.string.detail_use_suggestion, label),
            )
        }
        IconButton(onClick = { onDismiss(key) }) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(Res.string.detail_dismiss_suggestion, label),
            )
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
