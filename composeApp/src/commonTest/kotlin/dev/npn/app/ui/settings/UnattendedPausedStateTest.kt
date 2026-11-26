package dev.npn.app.ui.settings

import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiSpendSummary
import dev.npn.core.ai.AiUsageKind
import dev.npn.core.ai.AiUsageTotals
import dev.npn.core.settings.SettingsSnapshot
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** When the AI pane says the spending limit has paused automatic AI. */
class UnattendedPausedStateTest {
    private val snapshot =
        SettingsSnapshot(
            installationId = "i",
            aiEnabled = true,
            aiProvider = AiProvider.OPENAI,
            aiApiKey = "k",
            aiModel = "gpt-5",
            aiOcrAuto = true,
            aiUnattendedLimitUsd = 10,
        )

    private fun spent(usd: Double?) =
        AiSpendSummary(
            last30Days =
                listOf(
                    AiUsageTotals(
                        provider = "OPENAI",
                        model = "gpt-5",
                        kind = AiUsageKind.OCR,
                        requests = 1,
                        inputTokens = 0,
                        outputTokens = 0,
                        estimatedCostUsd = usd,
                        unpricedRequests = if (usd == null) 1 else 0,
                        estimatedRequests = 0,
                    ),
                ),
        )

    @Test
    fun theLimitReachedWithAutomaticAiOnShowsThePause() {
        SettingsUiState(snapshot = snapshot, aiSpend = spent(10.0)).aiUnattendedPaused shouldBe true
        SettingsUiState(snapshot = snapshot, aiSpend = spent(9.99)).aiUnattendedPaused shouldBe false
    }

    @Test
    fun noPauseIsShownWithoutAutomaticAiOrWithoutALimit() {
        SettingsUiState(snapshot = snapshot.copy(aiOcrAuto = false), aiSpend = spent(50.0)).aiUnattendedPaused shouldBe
            false
        SettingsUiState(snapshot = snapshot.copy(aiUnattendedLimitUsd = 0), aiSpend = spent(50.0))
            .aiUnattendedPaused shouldBe false
    }
}
