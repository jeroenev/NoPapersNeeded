@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.SuggestedFieldValue
import dev.npn.app.ui.common.TagEditor
import dev.npn.app.ui.common.TagSuggestionLabel
import dev.npn.app.ui.common.TestTags
import dev.npn.app.ui.common.enumNameSaver
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.DocListItem
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.reviewMarkerIsAuthoritative
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_added
import no_papers_needed.composeapp.generated.resources.detail_dismiss_all
import no_papers_needed.composeapp.generated.resources.detail_dismiss_suggestion
import no_papers_needed.composeapp.generated.resources.detail_dismiss_tag
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable_hint
import no_papers_needed.composeapp.generated.resources.detail_file
import no_papers_needed.composeapp.generated.resources.detail_location
import no_papers_needed.composeapp.generated.resources.detail_mark_needs_review
import no_papers_needed.composeapp.generated.resources.detail_mark_reviewed
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
import no_papers_needed.composeapp.generated.resources.detail_use_suggestion
import no_papers_needed.composeapp.generated.resources.ux_tags_saved
import org.jetbrains.compose.resources.stringResource

/**
 * Side panel for one document with three tabs: **Details** (editable metadata — the registry's
 * fields, tags, and file/OCR facts), **Text** (the extracted OCR/embedded full text), and
 * **History** (immutable journal timeline). Text fields offer existing values as suggestions.
 *
 * Everything shown comes from [state] (assembled by `DocDetailViewModel.metadata`) and [item],
 * the document as its draft shows it ([refused] naming the draft values that are entries their
 * editor refused), and everything it can do from [actions] (provided by [DocumentEditor], which
 * routes field edits into the draft).
 *
 * When [previewTab] is provided (a compact window, where the page viewer cannot sit beside the
 * tabs), it is prepended as an extra **Preview** tab and selected initially.
 */
@Composable
internal fun MetadataTabs(
    state: MetadataUiState,
    item: DocListItem,
    refused: Set<String>,
    actions: MetadataActions,
    previewTab: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tabs =
        remember(previewTab != null) {
            if (previewTab != null) PanelTab.entries.toList() else PanelTab.entries.filter { it != PanelTab.PREVIEW }
        }
    var savedTab by rememberSaveable(stateSaver = enumNameSaver()) { mutableStateOf(tabs.first()) }
    val tab = savedTab.takeIf { it in tabs } ?: tabs.first()
    LaunchedEffect(tab) {
        if (tab == PanelTab.HISTORY) {
            actions.loadHistory()
        }
    }
    // Shared with OcrTextTab so Ctrl+F can move focus into its find field. Focus is only requested
    // when Ctrl+F raises the counter, never on a plain tab tap (which would pop the keyboard).
    val findFocus = remember { FocusRequester() }
    var findRequestCounter by remember { mutableIntStateOf(0) }
    Column(
        modifier.findKeys {
            savedTab = PanelTab.TEXT
            findRequestCounter++
        },
    ) {
        PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), edgePadding = 0.dp) {
            tabs.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { savedTab = t },
                    modifier = Modifier.testTag(TestTags.panelTab(t.name)),
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
                    item = item,
                    refused = refused,
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
                    onRefresh = { actions.loadHistory(refresh = true) },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
}

/** What stands in for a document that is gone: trashed, or deleted on another replica while open. */
@Composable
internal fun DocumentUnavailable(modifier: Modifier = Modifier) {
    EmptyState(
        icon = Icons.Filled.Delete,
        title = stringResource(Res.string.detail_document_unavailable),
        modifier = modifier,
        subtitle = stringResource(Res.string.detail_document_unavailable_hint),
    )
}

private enum class PanelTab { PREVIEW, DETAILS, TEXT, HISTORY }

@Composable
private fun DetailsTab(
    state: MetadataUiState,
    item: DocListItem,
    refused: Set<String>,
    actions: MetadataActions,
    modifier: Modifier = Modifier,
) {
    val doc = item.doc
    val vocabulary = state.vocabulary

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ReviewStatusSection(state.document.reviewMode, state.document.reviewed, actions::setReviewed)
        FieldsSection(
            doc = doc,
            refused = refused,
            defs = state.document.fieldDefs,
            vocabulary = vocabulary,
            actions = actions,
        )
        AiMetadataSuggestions(
            available = state.ai.available,
            state = state.ai.metadata,
            labels = state.document.fieldDefs.associate { it.key to it.label },
            current = doc.fields,
            actions = actions,
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
            onAdd = actions::addTag,
            onRemove = actions::removeTag,
            onAddWithStyle = actions::addTagWithStyle,
        )

        AiTagSuggestions(
            available = state.ai.available,
            state = state.ai.tags,
            actions = actions,
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
                onSetEngine = actions::setOcrEngine,
            )
        }
    }
}

@Composable
private fun ReviewStatusSection(
    reviewMode: ReviewMode,
    reviewed: Boolean,
    onReviewedChange: (Boolean) -> Unit,
) {
    if (!reviewMode.reviewMarkerIsAuthoritative) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReviewedBadge(reviewed)
        // Writes straight through (it also commits the draft), so it follows the save bar's rules.
        OutlinedButton(onClick = { onReviewedChange(!reviewed) }, enabled = !LocalDocumentWriteBlocked.current) {
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
 * ([available]). Offers a "Suggest tags" button (spinner while running) and renders each
 * suggestion as an [InputChip] visually distinct from real tags: tapping the chip (or its leading +)
 * accepts it, the trailing × dismisses it; a "Dismiss all" button clears the lot.
 */
@Composable
private fun AiTagSuggestions(
    available: Boolean,
    state: AiTagState,
    actions: DocumentWrites,
) {
    if (!available) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AiSuggestButton(
            label = stringResource(Res.string.detail_suggest_tags),
            busyLabel = stringResource(Res.string.detail_suggesting_tags),
            suggesting = state.suggesting,
            onClick = actions::suggestAiTags,
        )
        if (state.suggestions.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                state.suggestions.forEach { suggestion ->
                    AiTagChip(suggestion, actions::acceptAiTag, actions::dismissAiTag)
                }
            }
            TextButton(onClick = actions::clearAiTagSuggestions) {
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
 * running) and renders each suggested field as a row labelled from the registry, showing the
 * [current] value it would replace the way the bulk Review does: the check accepts it through the
 * normal commit path, the × dismisses it.
 */
@Composable
private fun AiMetadataSuggestions(
    available: Boolean,
    state: AiMetadataState,
    labels: Map<String, String>,
    current: Map<String, String>,
    actions: DocumentWrites,
) {
    if (!available) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AiSuggestButton(
            label = stringResource(Res.string.detail_suggest_metadata),
            busyLabel = stringResource(Res.string.detail_suggesting_metadata),
            suggesting = state.suggesting,
            onClick = actions::suggestAiMetadata,
        )
        for ((key, value) in state.suggestion.values) {
            AiMetadataSuggestionRow(
                label = labels[key] ?: key,
                value = value,
                replaces = current[key]?.takeIf { it.isNotBlank() && it != value },
                key = key,
                onAccept = { actions.acceptAiMetadata(it) },
                onDismiss = actions::dismissAiMetadata,
            )
        }
    }
}

/** An AI "suggest" action, disabled with a spinner announcing [busyLabel] while [suggesting]. */
@Composable
private fun AiSuggestButton(
    label: String,
    busyLabel: String,
    suggesting: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClick, enabled = !suggesting) {
            Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(label, modifier = Modifier.padding(start = 8.dp))
        }
        if (suggesting) {
            CircularProgressIndicator(
                Modifier.size(16.dp).semantics {
                    contentDescription = busyLabel
                    liveRegion = LiveRegionMode.Polite
                },
                strokeWidth = 2.dp,
            )
        }
    }
}

/** One suggested field value, with what it replaces, and accept (check) and dismiss (×) actions. */
@Composable
private fun AiMetadataSuggestionRow(
    label: String,
    value: String,
    replaces: String?,
    key: String,
    onAccept: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SuggestedFieldValue(label = label, value = value, replaces = replaces, modifier = Modifier.weight(1f))
        IconButton(onClick = { onAccept(key) }, enabled = !LocalDocumentWriteBlocked.current) {
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
        HelpText(label, modifier = Modifier.padding(end = 4.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
