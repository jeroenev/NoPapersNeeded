@file:OptIn(ExperimentalLayoutApi::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.SuggestedFieldValue
import dev.npn.app.ui.common.TagSuggestionLabel
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.core.ai.TagSuggestion
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.detail_dismiss_all
import no_papers_needed.composeapp.generated.resources.detail_dismiss_suggestion
import no_papers_needed.composeapp.generated.resources.detail_dismiss_tag
import no_papers_needed.composeapp.generated.resources.detail_suggest_metadata
import no_papers_needed.composeapp.generated.resources.detail_suggest_tags
import no_papers_needed.composeapp.generated.resources.detail_suggesting_metadata
import no_papers_needed.composeapp.generated.resources.detail_suggesting_tags
import no_papers_needed.composeapp.generated.resources.detail_use_suggestion
import org.jetbrains.compose.resources.stringResource

/**
 * AI new-tag suggestions for the current doc. Hidden entirely unless the AI endpoint is configured
 * ([available]). Offers a "Suggest tags" button (spinner while running) and renders each
 * suggestion as an [InputChip] visually distinct from real tags: tapping the chip (or its leading +)
 * accepts it, the trailing × dismisses it; a "Dismiss all" button clears the lot.
 */
@Composable
internal fun AiTagSuggestions(
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
        TooltipIconButton(
            Icons.Filled.Close,
            stringResource(Res.string.detail_dismiss_tag, tag),
            onClick = { onDismiss(tag) },
        )
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
internal fun AiMetadataSuggestions(
    available: Boolean,
    state: AiMetadataState,
    labels: Map<String, String>,
    current: Map<String, String>,
    /** False while the document cannot be written; accepting a suggestion writes. */
    acceptEnabled: Boolean,
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
                acceptEnabled = acceptEnabled,
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
    acceptEnabled: Boolean,
    onAccept: (String) -> Unit,
    onDismiss: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SuggestedFieldValue(label = label, value = value, replaces = replaces, modifier = Modifier.weight(1f))
        TooltipIconButton(
            Icons.Filled.Check,
            stringResource(Res.string.detail_use_suggestion, label),
            onClick = { onAccept(key) },
            enabled = acceptEnabled,
        )
        TooltipIconButton(
            Icons.Filled.Close,
            stringResource(Res.string.detail_dismiss_suggestion, label),
            onClick = { onDismiss(key) },
        )
    }
}
