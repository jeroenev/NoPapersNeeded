package dev.npn.core.ai

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The pre-flight arithmetic, the price table and the per-run budget, without an endpoint. */
class AiSpendTest {
    @Test
    fun oneUnpricedRequestLeavesThePricedSpendStandingAndCountsWhatHadNoPrice() {
        val priced = AiUsageTotals("p", "m", AiUsageKind.CHAT, 1, 10, 2, 0.5, 0, 0)
        val unpriced = AiUsageTotals("p", "other", AiUsageKind.OCR, 1, 10, 2, null, 1, 0)
        listOf(priced).spendTotal() shouldBe AiSpendTotal(0.5, pricedRequests = 1, unpricedRequests = 0)
        listOf(priced, unpriced).spendTotal() shouldBe AiSpendTotal(0.5, pricedRequests = 1, unpricedRequests = 1)
        listOf(priced.copy(requests = 3, unpricedRequests = 1)).spendTotal() shouldBe
            AiSpendTotal(0.5, pricedRequests = 2, unpricedRequests = 1)
    }

    @Test
    fun aLongerDocumentAndMoreFieldsEstimateMoreTokens() {
        val short =
            AiEstimator.suggest(
                listOf(AiEstimateDoc(textChars = 1_000)),
                fields = 0,
                tags = true,
                tagRemovals = false,
                customPrompt = false,
            )
        val long =
            AiEstimator.suggest(
                listOf(AiEstimateDoc(textChars = 100_000)),
                fields = 3,
                tags = true,
                tagRemovals = true,
                customPrompt = true,
            )

        (long.inputTokens > short.inputTokens) shouldBe true
        (long.outputTokens > short.outputTokens) shouldBe true
    }

    @Test
    fun moreDocumentsMeanMoreRequestsAndOutputTokens() {
        val one =
            AiEstimator.suggest(
                listOf(AiEstimateDoc(textChars = 4_000)),
                fields = 1,
                tags = true,
                tagRemovals = false,
                customPrompt = false,
            )
        val five =
            AiEstimator.suggest(
                List(5) { AiEstimateDoc(textChars = 4_000) },
                fields = 1,
                tags = true,
                tagRemovals = false,
                customPrompt = false,
            )

        (five.inputTokens > one.inputTokens) shouldBe true
        (five.outputTokens > one.outputTokens) shouldBe true
    }

    @Test
    fun ocrIsPricedPerPage() {
        AiEstimator.ocr(4).inputTokens shouldBe 4L * AiEstimator.OCR_INPUT_TOKENS_PER_PAGE
        AiEstimator.ocr(4).outputTokens shouldBe 4L * AiEstimator.OCR_OUTPUT_TOKENS_PER_PAGE
    }

    @Test
    fun costScalesWithThePriceAndTokens() {
        AiPrice(0.40, 1.60).cost(inputTokens = 1_000_000, outputTokens = 1_000_000) shouldBe (0.40 + 1.60)
        AiPrice(0.40, 1.60).cost(inputTokens = null, outputTokens = 500_000) shouldBe 0.80
    }

    @Test
    fun scaledEstimateKeepsItsPerRequestShapeAndCap() {
        val base =
            AiRunEstimate(
                documents = 2,
                requests = 2,
                pages = 0,
                inputTokens = 2_000,
                outputTokens = 400,
                estimatedCostUsd = 0.02,
                priceKnown = true,
                requestCap = 50,
            )

        val scaled = base.scaledTo(10)

        scaled.documents shouldBe 10
        scaled.requests shouldBe 10
        scaled.inputTokens shouldBe 10_000
        scaled.outputTokens shouldBe 2_000
        checkNotNull(scaled.estimatedCostUsd)
        scaled.estimatedCostUsd shouldBe 0.1
        scaled.requestCap shouldBe 50
        scaled.exceedsCap shouldBe false
    }

    @Test
    fun theBudgetStopsExactlyAtTheCapEvenUnderConcurrentAsks() =
        runTest {
            val budget = AiRunBudget(cap = 3, initiallyUsed = 1)

            budget.tryAcquire() shouldBe true
            budget.tryAcquire() shouldBe true
            budget.tryAcquire() shouldBe false
            budget.usedRequests() shouldBe 3
        }

    @Test
    fun aBudgetCarriesLedgerRequestsFromBeforeARestart() =
        runTest {
            val budget = AiRunBudget(cap = 2, initiallyUsed = 2)

            budget.tryAcquire() shouldBe false
            budget.usedRequests() shouldBe 2
        }
}
