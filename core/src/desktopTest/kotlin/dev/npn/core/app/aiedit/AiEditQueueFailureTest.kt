package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiEditRequest
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import dev.npn.core.work.EndpointHealth
import dev.npn.core.work.backoffMs
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AiEditQueueFailureTest {
    @Test
    fun aWholeFailedChunkIsSystemicTheRunBacksOffAndNoDocumentIsCharged() =
        runTest {
            withQueue { rig ->
                rig.ai.failWith = { error("endpoint down") }
                val jobId = rig.enqueue("a", "b")

                // False parks the drain rather than firing the rest of the backlog at a dead endpoint.
                rig.queue.processNext() shouldBe false

                // Documents keep their clean slate: an outage must not spend a run's attempts.
                rig.rows(jobId).map { it.attempts } shouldContainExactly listOf(0, 0)
                val job = rig.job(jobId).shouldNotBeNull()
                job.attempts shouldBe 1
                job.nextAttemptAt shouldBe rig.clock.nowMillis() + backoffMs(1)
                job.lastError shouldBe "endpoint down"
            }
        }

    @Test
    fun aSystemicFailureParksTheWholeQueueNotJustTheRunThatHitIt() =
        runTest {
            withQueue { rig ->
                // Two runs: the second has never failed and so carries no backoff of its own. Only
                // the endpoint-level cooldown can stop it being fired at the same dead endpoint.
                rig.documents.documents.value = listOf(listItem("a"), listItem("b"))
                rig.queue.enqueueEditRun(listOf("a"), AiEditRequest())
                rig.queue.enqueueEditRun(listOf("b"), AiEditRequest())
                rig.ai.failWith = { error("endpoint down") }

                rig.queue.processNext() shouldBe false
                rig.queue.processNext() shouldBe false

                rig.ai.suggestEditsCalls shouldHaveSize 1

                rig.clock.advance(EndpointHealth.ENDPOINT_COOLDOWN_MS)
                rig.ai.failWith = null
                rig.ai.edit = suggestion()
                rig.queue.processNext() shouldBe true
                rig.ai.suggestEditsCalls shouldHaveSize 2
            }
        }

    @Test
    fun oneFailingDocumentAmongSuccessesIsChargedTheAttemptAlone() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                rig.ai.failWith = { docId -> if (docId == "b") error("rejected") }
                val jobId = rig.enqueue("a", "b")

                // The endpoint answered "a", so this failure is about "b", not about the endpoint.
                rig.queue.processNext() shouldBe true

                val byId = rig.rows(jobId).associateBy { it.docId }
                byId.getValue("a").suggestionJson.shouldNotBeNull()
                byId.getValue("b").attempts shouldBe 1
                byId.getValue("b").failed shouldBe false
                byId.getValue("b").lastError shouldBe "rejected"
                rig.job(jobId).shouldNotBeNull().attempts shouldBe 0
            }
        }

    /** The queue view shows the stored error; a blank one would show an empty failure. */
    @Test
    fun aDocumentFailingWithABlankMessageStillStoresWhatFailed() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                rig.ai.failWith = { docId -> if (docId == "b") error(" ") }
                val jobId = rig.enqueue("a", "b")

                rig.queue.processNext() shouldBe true

                rig.rows(jobId).single { it.docId == "b" }.lastError shouldBe "IllegalStateException"
            }
        }

    @Test
    fun aRunParkedByAFailureWithABlankMessageStillStoresWhatFailed() =
        runTest {
            withQueue { rig ->
                rig.ai.failWith = { error("") }
                val jobId = rig.enqueue("a")

                rig.queue.processNext() shouldBe false

                rig.job(jobId).shouldNotBeNull().lastError shouldBe "IllegalStateException"
            }
        }

    @Test
    fun aDocumentTheEndpointAlwaysRejectsIsGivenUpOnSoItsRunStillFinishes() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                rig.ai.failWith = { docId -> if (docId == "b") error("rejected") }
                val jobId = rig.enqueue("a", "b")

                // "b" ends up alone in its chunk, where a total failure first reads as an outage.
                // Past the systemic budget it is charged, and at the cap the run is finished.
                rig.drainUntilSettled()

                val row = rig.rows(jobId).single { it.docId == "b" }
                row.attempts shouldBe AiEditQueue.MAX_DOC_ATTEMPTS
                row.failed shouldBe true
                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()
                review.failures shouldBe 1
                review.docs.map { it.docId } shouldContainExactly listOf("a")
            }
        }

    @Test
    fun anOutageInsideTheSystemicBudgetCostsTheDocumentsNothing() =
        runTest {
            withQueue { rig ->
                rig.ai.failWith = { error("endpoint down") }
                val jobId = rig.enqueue("a", "b")

                repeat(AiEditQueue.MAX_JOB_ATTEMPTS) {
                    rig.clock.advance(OUTAGE_STEP_MS)
                    rig.queue.processNext() shouldBe false
                }

                rig.rows(jobId).map { it.attempts } shouldContainExactly listOf(0, 0)
                rig.job(jobId).shouldNotBeNull().attempts shouldBe AiEditQueue.MAX_JOB_ATTEMPTS

                // ...and the run picks straight up once the endpoint is back.
                rig.ai.failWith = null
                rig.ai.edit = suggestion()
                rig.clock.advance(OUTAGE_STEP_MS)
                rig.queue.processNext() shouldBe true
                rig.job(jobId).shouldNotBeNull().attempts shouldBe 0
            }
        }

    @Test
    fun progressClearsABackoffEarnedWhileTheEndpointWasDown() =
        runTest {
            withQueue { rig ->
                rig.ai.failWith = { error("endpoint down") }
                val jobId = rig.enqueue("a")
                rig.queue.processNext()
                rig.job(jobId).shouldNotBeNull().attempts shouldBe 1

                rig.ai.failWith = null
                rig.ai.edit = suggestion()
                rig.clock.advance(EndpointHealth.ENDPOINT_COOLDOWN_MS + backoffMs(1))
                rig.queue.processNext() shouldBe true

                val job = rig.job(jobId).shouldNotBeNull()
                job.attempts shouldBe 0
                job.lastError shouldBe null
            }
        }
}
