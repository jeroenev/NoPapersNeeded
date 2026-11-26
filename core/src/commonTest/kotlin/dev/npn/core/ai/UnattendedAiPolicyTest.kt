package dev.npn.core.ai

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The unattended spending limit: what pauses automatic AI and what it never touches. */
class UnattendedAiPolicyTest {
    private val config =
        AiConfig(
            enabled = true,
            provider = AiProvider.OPENAI,
            model = "gpt-5",
            apiKey = "k",
            autoOnOcr = true,
            ocrAuto = true,
            unattendedLimitUsd = 10,
        )

    private fun spent(
        usd: Double?,
        requests: Int = 1,
    ) = AiUsageTotals(
        provider = "OPENAI",
        model = "gpt-5",
        kind = AiUsageKind.OCR,
        requests = requests,
        inputTokens = 0,
        outputTokens = 0,
        estimatedCostUsd = usd,
        unpricedRequests = if (usd == null) requests else 0,
        estimatedRequests = 0,
    )

    private fun policy(
        config: AiConfig = this.config,
        spent: List<AiUsageTotals>,
    ) = UnattendedAiPolicy(spentLast30Days = { spent }, readConfig = { config })

    @Test
    fun belowTheLimitAutomaticAiRuns() =
        runTest {
            val policy = policy(spent = listOf(spent(9.99)))

            policy.autoOcr() shouldBe true
            policy.mayEnrichNow() shouldBe true
            policy.paused() shouldBe false
        }

    @Test
    fun reachingTheLimitPausesAutomaticOcrAndTheEnrichmentDrain() =
        runTest {
            val policy = policy(spent = listOf(spent(6.0), spent(4.0)))

            policy.paused() shouldBe true
            policy.autoOcr() shouldBe false
            policy.mayEnrichNow() shouldBe false
            // Documents are still queued, so none is lost while the limit holds them back.
            policy.autoEnrich() shouldBe true
        }

    @Test
    fun noLimitNeverPauses() =
        runTest {
            policy(config.copy(unattendedLimitUsd = 0), spent = listOf(spent(1_000.0))).paused() shouldBe false
        }

    @Test
    fun requestsWithoutAPriceDoNotCountTowardsTheLimit() =
        runTest {
            policy(spent = listOf(spent(null, requests = 10_000), spent(1.0))).paused() shouldBe false
        }

    @Test
    fun theLimitIsReadPerCallSoRaisingItResumesAtOnce() =
        runTest {
            var current = config
            val policy = UnattendedAiPolicy(spentLast30Days = { listOf(spent(10.0)) }, readConfig = { current })
            policy.paused() shouldBe true

            current = config.copy(unattendedLimitUsd = 20)

            policy.paused() shouldBe false
        }
}
