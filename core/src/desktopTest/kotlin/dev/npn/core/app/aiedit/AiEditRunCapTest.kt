package dev.npn.core.app.aiedit

import dev.npn.core.ai.TagSuggestion
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The interactive run's per-run request cap: where it stops, and that it always says so. */
class AiEditRunCapTest {
    private fun harness(
        docs: Int,
        cap: Int,
    ) = AiEditRunHarness(maxRunRequests = cap).apply {
        documents.documents.value = (1..docs).map { listItem("d$it") }
        ai.edit = edit(addTags = listOf(TagSuggestion("t", isNew = false)))
    }

    @Test
    fun aCapInsideTheBatchStopsDispatchAndCountsEveryUnaskedDocument() =
        runTest {
            val h = harness(docs = 6, cap = 2)

            val review = h.run.suggest(key(batchSize = 3)).shouldBeInstanceOf<BatchResult.Review>()

            h.ai.suggestEditsCalls shouldHaveSize 2
            review.capReached shouldBe true
            // One left unasked in this batch, three more in the scope beyond it.
            review.skipped shouldBe 4
            h.run.moreRemain(key(batchSize = 3)) shouldBe false
        }

    @Test
    fun aBatchThatSpendsTheLastRequestExactlyStillReportsTheCap() =
        runTest {
            // Cap 4 over batches of 2: the second batch skips nothing, yet the run ends there.
            val h = harness(docs = 6, cap = 4)
            h.run
                .suggest(key(batchSize = 2))
                .shouldBeInstanceOf<BatchResult.Review>()
                .capReached shouldBe false

            val second = h.run.suggest(key(batchSize = 2)).shouldBeInstanceOf<BatchResult.Review>()

            second.docs shouldHaveSize 2
            second.capReached shouldBe true
            second.skipped shouldBe 2
            h.run.moreRemain(key(batchSize = 2)) shouldBe false
        }

    @Test
    fun theLastBatchOfTheScopeAtTheCapIsNotReportedAsStopped() =
        runTest {
            val h = harness(docs = 2, cap = 2)

            val review = h.run.suggest(key(batchSize = 2)).shouldBeInstanceOf<BatchResult.Review>()

            review.capReached shouldBe false
            review.skipped shouldBe 0
        }

    @Test
    fun anExhaustedRunAsksNothingAndCountsTheWholeRemainder() =
        runTest {
            val h = harness(docs = 5, cap = 1)
            h.run.suggest(key(batchSize = 1))

            val after = h.run.suggest(key(batchSize = 1)).shouldBeInstanceOf<BatchResult.Review>()

            h.ai.suggestEditsCalls shouldHaveSize 1
            after.docs shouldBe emptyList()
            after.capReached shouldBe true
            after.skipped shouldBe 4
        }

    @Test
    fun aThrottledCallDoesNotSpendTheCapSoTheRetryInTheNextBatchStillFits() =
        runTest {
            // The cap fits the scope exactly; a 429 on one document must not leave it unasked.
            val h = harness(docs = 3, cap = 3)
            var throttled = true
            h.ai.failWith = { docId -> if (docId == "d1" && throttled) error("429 Too Many Requests") }

            val first = h.run.suggest(key(batchSize = 3)).shouldBeInstanceOf<BatchResult.Review>()
            first.failures shouldBe 1
            first.capReached shouldBe false
            h.run.moreRemain(key(batchSize = 3)) shouldBe true

            throttled = false
            val retry = h.run.suggest(key(batchSize = 3)).shouldBeInstanceOf<BatchResult.Review>()

            retry.docs.map { it.docId } shouldBe listOf("d1")
            retry.capReached shouldBe false
        }
}
