package dev.npn.core.app

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** What Activity reports as blocked rather than as work in motion, and what it leaves to its rows. */
class WorkBlockTest {
    private fun work(
        stage: WorkStage,
        lastError: String? = null,
    ) = QueuedWork(
        docId = "doc",
        displayTitle = "Invoice",
        stage = stage,
        state = if (lastError == null) WorkState.WAITING else WorkState.RETRYING,
        attempts = if (lastError == null) 0 else 1,
        updatedAt = 0,
        lastError = lastError,
    )

    private val ready = WorkCapabilities(aiEndpointConfigured = true)

    @Test
    fun workWithEverythingItNeedsIsNotBlocked() {
        work(WorkStage.OCR).blockedBy(ready) shouldBe null
        work(WorkStage.AI_ENRICHMENT).blockedBy(ready) shouldBe null
    }

    @Test
    fun ocrWithoutAnEngineMayStillExtractEmbeddedText() {
        work(WorkStage.OCR).blockedBy(ready) shouldBe null
    }

    @Test
    fun enrichmentWithoutAnEndpointIsBlocked() {
        work(WorkStage.AI_ENRICHMENT).blockedBy(ready.copy(aiEndpointConfigured = false)) shouldBe
            WorkBlock.AI_ENDPOINT_UNCONFIGURED
    }

    /** A failed row with an endpoint configured keeps its own retry state, not an endpoint verdict. */
    @Test
    fun aFailedEnrichmentRowIsNotAnEndpointVerdict() {
        work(WorkStage.AI_ENRICHMENT, lastError = "connection refused").blockedBy(ready) shouldBe null
    }
}
