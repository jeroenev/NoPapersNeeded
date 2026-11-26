package dev.npn.app.ui.settings

import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.formatUsd
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiUsageKind
import dev.npn.core.ai.AiUsageTotals
import dev.npn.core.ai.spendTotal
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_approx
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_chat
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_enrichment
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_other
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_suggest
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_model_line
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_model_line_partial
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_model_line_unpriced
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_total
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_total_partial
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_total_unpriced

/**
 * The spend ledger's lines as the AI pane shows them. A line is one provider, model and kind of
 * work; token counts that hold an estimate (the provider reported no usage for some of the line's
 * requests) are marked ≈. A dollar amount is never hidden for want of a price elsewhere: what was
 * priced is shown as "at least", next to how many requests had no price.
 */
internal object SpendText {
    fun line(totals: AiUsageTotals): UiText {
        val provider = AiProvider.entries.firstOrNull { it.name == totals.provider }?.displayName ?: totals.provider
        val estimated = totals.estimatedRequests > 0
        val args =
            arrayOf(
                provider,
                totals.model,
                kind(totals.kind),
                totals.requests,
                tokens(totals.inputTokens, estimated),
                tokens(totals.outputTokens, estimated),
            )
        val priced = totals.estimatedCostUsd
        return when {
            priced == null -> {
                uiText(Res.string.common_settings_ai_spend_model_line_unpriced, *args)
            }

            totals.unpricedRequests > 0 -> {
                uiText(
                    Res.plurals.common_settings_ai_spend_model_line_partial,
                    totals.unpricedRequests,
                    *args,
                    formatUsd(priced),
                    totals.unpricedRequests,
                )
            }

            else -> {
                uiText(Res.string.common_settings_ai_spend_model_line, *args, formatUsd(priced))
            }
        }
    }

    fun total(lines: List<AiUsageTotals>): UiText {
        val estimated = lines.any { it.estimatedRequests > 0 }
        val requests = lines.sumOf { it.requests }
        val input = tokens(lines.sumOf { it.inputTokens }, estimated)
        val output = tokens(lines.sumOf { it.outputTokens }, estimated)
        val spend = lines.spendTotal()
        return when {
            spend.pricedRequests == 0 -> {
                uiText(Res.string.common_settings_ai_spend_total_unpriced, requests, input, output)
            }

            spend.unpricedRequests > 0 -> {
                uiText(
                    Res.plurals.common_settings_ai_spend_total_partial,
                    spend.unpricedRequests,
                    requests,
                    input,
                    output,
                    formatUsd(spend.pricedUsd),
                    spend.unpricedRequests,
                )
            }

            else -> {
                uiText(Res.string.common_settings_ai_spend_total, requests, input, output, formatUsd(spend.pricedUsd))
            }
        }
    }

    private fun tokens(
        count: Long,
        estimated: Boolean,
    ): Any = if (estimated) uiText(Res.string.common_settings_ai_spend_approx, count) else count

    private fun kind(kind: AiUsageKind?): UiText =
        uiText(
            when (kind) {
                AiUsageKind.CHAT -> Res.string.common_settings_ai_spend_kind_chat
                AiUsageKind.SUGGEST -> Res.string.common_settings_ai_spend_kind_suggest
                AiUsageKind.ENRICHMENT -> Res.string.common_settings_ai_spend_kind_enrichment
                AiUsageKind.OCR -> Res.string.common_settings_ai_spend_kind_ocr
                null -> Res.string.common_settings_ai_spend_kind_other
            },
        )
}
