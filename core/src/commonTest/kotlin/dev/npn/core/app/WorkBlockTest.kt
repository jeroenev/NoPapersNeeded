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

    private val ready = WorkCapabilities(ocrEngineAvailable = true, aiEndpointConfigured = true)

    @Test
    fun workWithEverythingItNeedsIsNotBlocked() {
        work(WorkStage.OCR).blockedBy(ready) shouldBe null
        work(WorkStage.AI_ENRICHMENT).blockedBy(ready) shouldBe null
    }

    @Test
    fun ocrWithoutAnEngineIsBlocked() {
        work(WorkStage.OCR).blockedBy(ready.copy(ocrEngineAvailable = false)) shouldBe WorkBlock.OCR_ENGINE_MISSING
        // The vision endpoint is an OCR engine too: with one configured, text recognition can run.
        work(WorkStage.OCR).blockedBy(ready.copy(ocrEngineAvailable = true)) shouldBe null
    }

    @Test
    fun enrichmentWithoutAnEndpointIsBlocked() {
        work(WorkStage.AI_ENRICHMENT).blockedBy(ready.copy(aiEndpointConfigured = false)) shouldBe
            WorkBlock.AI_ENDPOINT_UNCONFIGURED
    }

    /** A configured endpoint that did not answer is blocked, though the row will keep retrying. */
    @Test
    fun enrichmentAgainstAnAnsweringEndpointIsNotBlocked() {
        work(WorkStage.AI_ENRICHMENT, lastError = "connection refused").blockedBy(ready) shouldBe
            WorkBlock.AI_ENDPOINT_UNREACHABLE
        work(WorkStage.AI_ENRICHMENT, lastError = "connection refused")
            .blockedBy(ready.copy(aiEndpointConfigured = false)) shouldBe WorkBlock.AI_ENDPOINT_UNCONFIGURED
    }

    /** An engine missing holds OCR back even after a failure: the engine is the first fix. */
    @Test
    fun aMissingEngineWinsOverAFailedRow() {
        work(
            WorkStage.OCR,
            lastError = "no readable ocr version",
        ).blockedBy(ready.copy(ocrEngineAvailable = false)) shouldBe
            WorkBlock.OCR_ENGINE_MISSING
    }
}
