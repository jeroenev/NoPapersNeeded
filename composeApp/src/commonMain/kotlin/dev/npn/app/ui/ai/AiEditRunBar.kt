package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.formatUsd
import dev.npn.core.ai.AiEditRequest.Companion.MAX_FIELDS_PER_RUN
import dev.npn.core.ai.AiRunEstimate
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_blocked_loading
import no_papers_needed.composeapp.generated.resources.ai_edit_blocked_no_documents
import no_papers_needed.composeapp.generated.resources.ai_edit_blocked_no_saved_view
import no_papers_needed.composeapp.generated.resources.ai_edit_blocked_no_task
import no_papers_needed.composeapp.generated.resources.ai_edit_blocked_nothing
import no_papers_needed.composeapp.generated.resources.ai_edit_confirm_spend_confirm
import no_papers_needed.composeapp.generated.resources.ai_edit_confirm_spend_title
import no_papers_needed.composeapp.generated.resources.ai_edit_start
import no_papers_needed.composeapp.generated.resources.ai_edit_start_background
import no_papers_needed.composeapp.generated.resources.ai_edit_summary_fields
import no_papers_needed.composeapp.generated.resources.ai_edit_summary_line
import no_papers_needed.composeapp.generated.resources.ai_edit_summary_nothing
import no_papers_needed.composeapp.generated.resources.ai_edit_summary_prompt
import no_papers_needed.composeapp.generated.resources.ai_edit_summary_requests
import no_papers_needed.composeapp.generated.resources.ai_edit_summary_tag_removals
import no_papers_needed.composeapp.generated.resources.ai_edit_summary_tags
import no_papers_needed.composeapp.generated.resources.ai_edit_too_many_fields
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_cap
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_cost
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_documents
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_over_cap
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_pages
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_requests
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_tokens
import no_papers_needed.composeapp.generated.resources.ai_spend_estimate_tokens_only
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_join_items
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Below this the bar stacks its summary above the button instead of sitting on one line. */
private val RunBarSingleRowWidth = 620.dp

/**
 * The standing answer to "what happens if I press this", pinned below the setup form.
 *
 * It keeps the scope, the requested changes, the price in model calls and the Start button in one
 * place that never scrolls away, so on a phone the decision and the count it rests on are never
 * at opposite ends of a long form.
 */
@Composable
internal fun RunBar(
    ui: AiEditUiState,
    plan: RunPlan,
    estimate: AiRunEstimate?,
    blocker: StartBlocker?,
    onStart: () -> Unit,
) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            val singleRow = maxWidth > RunBarSingleRowWidth
            val summary: @Composable () -> Unit = {
                RunSummary(ui, plan, blocker, Modifier.fillMaxWidth())
            }
            val action: @Composable () -> Unit = {
                StartButton(
                    background = plan.background,
                    enabled = blocker == null,
                    onClick = { if (plan.needsConfirmation) confirming = true else onStart() },
                )
            }
            if (singleRow) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(Modifier.weight(1f)) { summary() }
                    action()
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    summary()
                    action()
                }
            }
        }
    }
    if (confirming) {
        ConfirmDialog(
            title = stringResource(Res.string.ai_edit_confirm_spend_title),
            confirmLabel = stringResource(Res.string.ai_edit_confirm_spend_confirm),
            dismissLabel = stringResource(Res.string.common_cancel),
            onConfirm = {
                confirming = false
                onStart()
            },
            onDismiss = { confirming = false },
        ) {
            RunEstimateBody(plan, estimate)
        }
    }
}

/**
 * What one Start would buy, as whole-sentence lines rather than a stitched paragraph: the scope,
 * the requests, the pages when the vision model is involved, estimated tokens and cost, and the
 * cap. The estimate line says "price unknown" instead of inventing money for a gateway or local
 * model.
 */
@Composable
private fun RunEstimateBody(
    plan: RunPlan,
    estimate: AiRunEstimate?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(Res.string.ai_spend_estimate_documents, estimate?.documents ?: plan.documents))
        Text(stringResource(Res.string.ai_spend_estimate_requests, estimate?.requests ?: plan.requests))
        if (estimate != null && estimate.pages > 0) {
            Text(stringResource(Res.string.ai_spend_estimate_pages, estimate.pages))
        }
        if (estimate != null) {
            val cost = estimate.estimatedCostUsd
            if (estimate.priceKnown && cost != null) {
                Text(stringResource(Res.string.ai_spend_estimate_tokens, estimate.inputTokens))
                Text(stringResource(Res.string.ai_spend_estimate_cost, formatUsd(cost)))
            } else {
                Text(stringResource(Res.string.ai_spend_estimate_tokens_only, estimate.inputTokens))
            }
        }
        Text(stringResource(Res.string.ai_spend_estimate_cap, estimate?.requestCap ?: plan.requestCap))
        if (estimate?.exceedsCap == true) {
            Text(
                stringResource(Res.string.ai_spend_estimate_over_cap),
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun RunSummary(
    ui: AiEditUiState,
    plan: RunPlan,
    blocker: StartBlocker?,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(summaryLine(ui, plan), style = MaterialTheme.typography.bodyMedium)
        // A blocker replaces the price line: while the run cannot start, what it would have cost
        // is not the thing the user needs to read.
        if (blocker != null) {
            Text(
                blockerText(blocker),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        } else {
            HelpText(pluralStringResource(Res.plurals.ai_edit_summary_requests, plan.requests, plan.requests))
        }
    }
}

/** "12 documents · tags, 3 fields" — the scope and what the run asks of each document. */
@Composable
private fun summaryLine(
    ui: AiEditUiState,
    plan: RunPlan,
): String {
    val scope = scopeCount(plan.documents, ui.batchSize)
    // Before a task is chosen the state still carries the defaults, but the user has not asked for
    // them, so listing them here would describe a run nobody configured.
    if (ui.task == null) return scope
    val changes =
        changeClauses(ui)
            .ifEmpty { listOf(stringResource(Res.string.ai_edit_summary_nothing)) }
            .reduce { list, clause -> stringResource(Res.string.common_join_items, list, clause) }
    return stringResource(Res.string.ai_edit_summary_line, scope, changes)
}

@Composable
private fun changeClauses(ui: AiEditUiState): List<String> {
    val fieldCount = ui.fieldOptions.count { it.value.enabled }
    return buildList {
        if (ui.includeTags) add(stringResource(Res.string.ai_edit_summary_tags))
        if (ui.includeTagRemovals) add(stringResource(Res.string.ai_edit_summary_tag_removals))
        if (fieldCount > 0) {
            add(pluralStringResource(Res.plurals.ai_edit_summary_fields, fieldCount, fieldCount))
        }
        if (ui.customPrompt.isNotBlank()) add(stringResource(Res.string.ai_edit_summary_prompt))
    }
}

@Composable
private fun blockerText(blocker: StartBlocker): String =
    when (blocker) {
        StartBlocker.NO_TASK -> stringResource(Res.string.ai_edit_blocked_no_task)
        StartBlocker.TOO_MANY_FIELDS -> stringResource(Res.string.ai_edit_too_many_fields, MAX_FIELDS_PER_RUN)
        StartBlocker.NOTHING_REQUESTED -> stringResource(Res.string.ai_edit_blocked_nothing)
        StartBlocker.NO_SAVED_VIEW -> stringResource(Res.string.ai_edit_blocked_no_saved_view)
        StartBlocker.LOADING_SCOPE -> stringResource(Res.string.ai_edit_blocked_loading)
        StartBlocker.NO_DOCUMENTS -> stringResource(Res.string.ai_edit_blocked_no_documents)
    }

@Composable
private fun StartButton(
    background: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Button(onClick = onClick, enabled = enabled) {
        Icon(Icons.Outlined.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
        Text(
            stringResource(
                if (background) Res.string.ai_edit_start_background else Res.string.ai_edit_start,
            ),
            Modifier.padding(start = 8.dp),
        )
    }
}
