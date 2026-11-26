package dev.npn.app.ui.settings

import dev.npn.app.ui.common.formatUsd
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiUsageKind
import dev.npn.core.ai.AiUsageTotals
import io.kotest.matchers.shouldBe
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_approx
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_chat
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_other
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_model_line
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_model_line_partial
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_model_line_unpriced
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_total
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_total_partial
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_total_unpriced
import kotlin.test.Test

class SpendTextTest {
    private fun totals(
        kind: AiUsageKind? = AiUsageKind.CHAT,
        requests: Int = 2,
        cost: Double? = 0.5,
        unpriced: Int = 0,
        estimated: Int = 0,
    ) = AiUsageTotals(
        provider = "OPENAI",
        model = "gpt-5",
        kind = kind,
        requests = requests,
        inputTokens = 1_000,
        outputTokens = 100,
        estimatedCostUsd = cost,
        unpricedRequests = unpriced,
        estimatedRequests = estimated,
    )

    @Test
    fun oneUnpricedRequestNoLongerHidesTheTotalItShowsWhatWasPricedAndHowManyWereNot() {
        val lines = listOf(totals(cost = 0.5), totals(kind = AiUsageKind.OCR, requests = 3, cost = null, unpriced = 3))

        SpendText.total(lines) shouldBe
            uiText(Res.plurals.common_settings_ai_spend_total_partial, 3, 5, 2_000L, 200L, formatUsd(0.5), 3)
    }

    @Test
    fun aFullyPricedTotalIsShownAsItIs() {
        SpendText.total(listOf(totals(cost = 1.25))) shouldBe
            uiText(Res.string.common_settings_ai_spend_total, 2, 1_000L, 100L, formatUsd(1.25))
    }

    @Test
    fun aTotalWithNothingPricedSaysThePriceIsUnknown() {
        SpendText.total(listOf(totals(cost = null, unpriced = 2))) shouldBe
            uiText(Res.string.common_settings_ai_spend_total_unpriced, 2, 1_000L, 100L)
    }

    @Test
    fun aLineNamesItsKindOfWork() {
        SpendText.line(totals(kind = AiUsageKind.OCR)) shouldBe
            uiText(
                Res.string.common_settings_ai_spend_model_line,
                "OpenAI",
                "gpt-5",
                uiText(Res.string.common_settings_ai_spend_kind_ocr),
                2,
                1_000L,
                100L,
                formatUsd(0.5),
            )
        SpendText.line(totals(kind = null, cost = null, unpriced = 2)) shouldBe
            uiText(
                Res.string.common_settings_ai_spend_model_line_unpriced,
                "OpenAI",
                "gpt-5",
                uiText(Res.string.common_settings_ai_spend_kind_other),
                2,
                1_000L,
                100L,
            )
    }

    @Test
    fun estimatedTokenCountsAreMarked() {
        val approx = { count: Long -> uiText(Res.string.common_settings_ai_spend_approx, count) }

        SpendText.line(totals(cost = 0.5, unpriced = 1, estimated = 1)) shouldBe
            uiText(
                Res.plurals.common_settings_ai_spend_model_line_partial,
                1,
                "OpenAI",
                "gpt-5",
                uiText(Res.string.common_settings_ai_spend_kind_chat),
                2,
                approx(1_000L),
                approx(100L),
                formatUsd(0.5),
                1,
            )
    }
}
