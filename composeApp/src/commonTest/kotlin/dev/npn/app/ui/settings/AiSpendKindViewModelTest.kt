package dev.npn.app.ui.settings

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.ui.common.formatUsd
import dev.npn.app.ui.common.uiText
import dev.npn.core.ai.AiSpendSummary
import dev.npn.core.ai.AiUsageKind
import dev.npn.core.ai.AiUsageTotals
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_approx
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_chat
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_ocr
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_kind_other
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_model_line
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_model_line_unpriced
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_total
import no_papers_needed.composeapp.generated.resources.common_settings_ai_spend_total_partial
import kotlin.test.Test

/** The spend page of one kind of work: only that kind, per provider and model, in both windows. */
class AiSpendKindViewModelTest : MainDispatcherTest() {
    private fun line(
        kind: AiUsageKind?,
        model: String,
        requests: Int = 1,
        cost: Double? = 0.5,
        estimated: Int = 0,
    ) = AiUsageTotals(
        provider = "OPENAI",
        model = model,
        kind = kind,
        requests = requests,
        inputTokens = 1_000,
        outputTokens = 100,
        estimatedCostUsd = cost,
        unpricedRequests = if (cost == null) requests else 0,
        estimatedRequests = estimated,
    )

    @Test
    fun aKindsPageShowsOnlyThatKindPerModelWithItsTotalInEachWindow() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.aiSpend.summaryFlow.value =
                AiSpendSummary(
                    last30Days = listOf(line(AiUsageKind.OCR, "gpt-5"), line(AiUsageKind.CHAT, "gpt-5")),
                    allTime =
                        listOf(
                            line(AiUsageKind.OCR, "gpt-5", requests = 3, cost = 1.5),
                            line(AiUsageKind.OCR, "custom-model", requests = 2, cost = null),
                            line(AiUsageKind.CHAT, "gpt-5"),
                        ),
                )
            val vm = AiSpendKindViewModel(session, AiSpendKinds.key(AiUsageKind.OCR))
            val states = collecting(vm.state)
            advanceUntilIdle()

            val state = states.last()
            state.title shouldBe uiText(Res.string.common_settings_ai_spend_kind_ocr)
            val ocr = uiText(Res.string.common_settings_ai_spend_kind_ocr)
            state.last30Days.lines shouldBe
                listOf(
                    uiText(
                        Res.string.common_settings_ai_spend_model_line,
                        "OpenAI",
                        "gpt-5",
                        ocr,
                        1,
                        1_000L,
                        100L,
                        formatUsd(0.5),
                    ),
                )
            state.last30Days.total shouldBe
                uiText(Res.string.common_settings_ai_spend_total, 1, 1_000L, 100L, formatUsd(0.5))
            state.allTime.lines shouldBe
                listOf(
                    uiText(
                        Res.string.common_settings_ai_spend_model_line,
                        "OpenAI",
                        "gpt-5",
                        ocr,
                        3,
                        1_000L,
                        100L,
                        formatUsd(1.5),
                    ),
                    uiText(
                        Res.string.common_settings_ai_spend_model_line_unpriced,
                        "OpenAI",
                        "custom-model",
                        ocr,
                        2,
                        1_000L,
                        100L,
                    ),
                )
            // The unpriced model does not hide what the priced one cost.
            state.allTime.total shouldBe
                uiText(Res.plurals.common_settings_ai_spend_total_partial, 2, 5, 2_000L, 200L, formatUsd(1.5), 2)
        }

    @Test
    fun estimatedTokensAreMarkedAndAKindWithNothingInAWindowHasNoTotalThere() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.aiSpend.summaryFlow.value =
                AiSpendSummary(allTime = listOf(line(AiUsageKind.CHAT, "gpt-5", estimated = 1)))
            val vm = AiSpendKindViewModel(session, AiSpendKinds.key(AiUsageKind.CHAT))
            val states = collecting(vm.state)
            advanceUntilIdle()

            val state = states.last()
            state.title shouldBe uiText(Res.string.common_settings_ai_spend_kind_chat)
            state.last30Days shouldBe SpendWindowText()
            val approx = { count: Long -> uiText(Res.string.common_settings_ai_spend_approx, count) }
            state.allTime.total shouldBe
                uiText(Res.string.common_settings_ai_spend_total, 1, approx(1_000L), approx(100L), formatUsd(0.5))
        }

    @Test
    fun aKindANewerBuildRecordedOpensAsOther() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.aiSpend.summaryFlow.value = AiSpendSummary(allTime = listOf(line(null, "gpt-5")))
            val vm = AiSpendKindViewModel(session, AiSpendKinds.OTHER)
            val states = collecting(vm.state)
            advanceUntilIdle()

            states.last().title shouldBe uiText(Res.string.common_settings_ai_spend_kind_other)
            states
                .last()
                .allTime.lines.size shouldBe 1
            AiSpendKinds.kindsIn(
                listOf(line(null, "a"), line(AiUsageKind.OCR, "b"), line(AiUsageKind.CHAT, "c")),
            ) shouldBe
                listOf(AiUsageKind.CHAT, AiUsageKind.OCR, null)
        }
}
