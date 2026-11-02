@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.ReviewedBadge
import dev.npn.app.ui.common.SectionHeading
import dev.npn.app.ui.common.TagEditor
import dev.npn.app.ui.common.TestTags
import dev.npn.app.ui.common.formatEpochDate
import dev.npn.core.app.DocListItem
import dev.npn.core.model.ReviewMode
import dev.npn.core.model.reviewMarkerIsAuthoritative
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_created
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable
import no_papers_needed.composeapp.generated.resources.detail_document_unavailable_hint
import no_papers_needed.composeapp.generated.resources.detail_file
import no_papers_needed.composeapp.generated.resources.detail_location
import no_papers_needed.composeapp.generated.resources.detail_mark_needs_review
import no_papers_needed.composeapp.generated.resources.detail_mark_reviewed
import no_papers_needed.composeapp.generated.resources.detail_ocr
import no_papers_needed.composeapp.generated.resources.detail_pages
import no_papers_needed.composeapp.generated.resources.detail_tab_details
import no_papers_needed.composeapp.generated.resources.detail_tab_history
import no_papers_needed.composeapp.generated.resources.detail_tab_preview
import no_papers_needed.composeapp.generated.resources.detail_tab_text
import no_papers_needed.composeapp.generated.resources.detail_tags
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
    writeBlocked: Boolean,
    actions: MetadataActions,
    /** The tab picked, null for the first; a tab the layout lacks (Preview) falls back to the first. */
    picked: PanelTab?,
    onPick: (PanelTab) -> Unit,
    previewTab: (@Composable (Modifier) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tabs =
        remember(previewTab != null) {
            if (previewTab != null) PanelTab.entries.toList() else PanelTab.entries.filter { it != PanelTab.PREVIEW }
        }
    val tab = picked?.takeIf { it in tabs } ?: tabs.first()
    DisposableEffect(tab) {
        if (tab == PanelTab.HISTORY) {
            actions.loadHistory()
        }
        onDispose {}
    }
    // Shared with OcrTextTab so Ctrl+F can move focus into its find field. Focus is only requested
    // when Ctrl+F raises the counter, never on a plain tab tap (which would pop the keyboard).
    val findFocus = remember { FocusRequester() }
    var findRequestCounter by remember { mutableIntStateOf(0) }
    Column(
        modifier.findKeys {
            onPick(PanelTab.TEXT)
            findRequestCounter++
        },
    ) {
        PrimaryScrollableTabRow(selectedTabIndex = tabs.indexOf(tab), edgePadding = 0.dp) {
            tabs.forEach { t ->
                Tab(
                    selected = tab == t,
                    onClick = { onPick(t) },
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
                    writeBlocked = writeBlocked,
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

/** The metadata panel's tabs; [PREVIEW] only where the page viewer cannot sit beside them. */
internal enum class PanelTab { PREVIEW, DETAILS, TEXT, HISTORY }

@Composable
private fun DetailsTab(
    state: MetadataUiState,
    item: DocListItem,
    refused: Set<String>,
    writeBlocked: Boolean,
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
        ReviewStatusSection(
            reviewMode = state.document.reviewMode,
            reviewed = state.document.reviewed,
            enabled = !writeBlocked,
            onReviewedChange = actions::setReviewed,
        )
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
            acceptEnabled = !writeBlocked,
            actions = actions,
        )

        HorizontalDivider()

        SectionHeading(stringResource(Res.string.detail_tags), style = MaterialTheme.typography.labelLarge)
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
            FactRow(stringResource(Res.string.detail_created), formatEpochDate(doc.createdAt))
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
    /** False while the document cannot be written; marking it reviewed writes. */
    enabled: Boolean,
    onReviewedChange: (Boolean) -> Unit,
) {
    if (!reviewMode.reviewMarkerIsAuthoritative) return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReviewedBadge(reviewed)
        // Writes straight through (it also commits the draft), so it follows the status line's rules.
        OutlinedButton(onClick = { onReviewedChange(!reviewed) }, enabled = enabled) {
            Text(
                stringResource(
                    if (reviewed) Res.string.detail_mark_needs_review else Res.string.detail_mark_reviewed,
                ),
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
