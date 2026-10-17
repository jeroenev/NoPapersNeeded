@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.core.app.aiedit

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AiEditQueueSuggestionTest {
    @Test
    fun aSubmittedRunStoresEachDocumentOnceInItsFirstSubmittedOrder() =
        runTest {
            withQueue { rig ->
                val jobId = rig.enqueue("b", "a", "b", "c", "a")

                rig.rows(jobId).map { it.docId } shouldContainExactly listOf("b", "a", "c")
                rig.rows(jobId).map { it.position } shouldContainExactly listOf(0, 1, 2)
                rig.job(jobId).shouldNotBeNull()
                rig.kicks shouldBe 1
            }
        }

    @Test
    fun aQueuedRunIsSuggestedInSubmittedOrderOneChunkPerDrainStep() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                val docIds = (1..SuggestionRunner.CONCURRENCY + 3).map { "d$it" }
                rig.documents.documents.value = docIds.map { listItem(it) }
                val jobId = rig.queue.enqueueEditRun(docIds, AiEditRequest())

                rig.queue.processNext() shouldBe true

                // One chunk only: the drainer loops, so a step must not swallow the whole run.
                rig.ai.suggestEditsCalls shouldContainExactly docIds.take(SuggestionRunner.CONCURRENCY)
                rig.rows(jobId).count { it.suggestionJson != null } shouldBe SuggestionRunner.CONCURRENCY

                rig.queue.processNext() shouldBe true
                rig.ai.suggestEditsCalls shouldContainExactly docIds
            }
        }

    /** Queued work never waits for the maintenance tick: the queue itself asks for its drain. */
    @Test
    fun queueingARunKicksTheQueuesOwnDrain() =
        runTest {
            withQueue { rig ->
                rig.enqueue("a")

                rig.kicks shouldBe 1
            }
        }

    @Test
    fun anUnconfiguredEndpointDoesNoWorkAtAll() =
        runTest {
            withQueue { rig ->
                rig.config = AiConfig.DISABLED
                rig.enqueue("a")

                rig.queue.processNext() shouldBe false

                rig.ai.suggestEditsCalls shouldBe emptyList()
            }
        }

    @Test
    fun duplicateDocumentsAreQueuedOnceBecauseEachOneCostsAModelCall() =
        runTest {
            withQueue { rig ->
                rig.documents.documents.value = listOf(listItem("a"))
                val jobId = rig.queue.enqueueEditRun(listOf("a", "a", "a"), AiEditRequest())

                rig.rows(jobId) shouldHaveSize 1
            }
        }

    @Test
    fun aDocumentTrashedSinceItWasQueuedIsDroppedRatherThanAskedAbout() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                val jobId = rig.enqueue("a", "b")
                // "a" leaves the library between queueing and the drain.
                rig.documents.documents.value = listOf(listItem("b"))

                rig.queue.processNext() shouldBe true

                rig.ai.suggestEditsCalls shouldContainExactly listOf("b")
                rig.rows(jobId).map { it.docId } shouldContainExactly listOf("b")
            }
        }

    @Test
    fun aRunWhoseEveryDocumentHasGoneIsDroppedRatherThanStranded() =
        runTest {
            withQueue { rig ->
                val jobId = rig.enqueue("a")
                rig.documents.documents.value = emptyList()

                rig.queue.processNext() shouldBe true

                rig.job(jobId) shouldBe null
            }
        }

    @Test
    fun aRunSurvivesTheProcessThatQueuedIt() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                val jobId = rig.enqueue("a")

                // A fresh queue instance over the same database: only the rows carry over.
                rig.newQueue().processNext() shouldBe true

                rig
                    .rows(jobId)
                    .single()
                    .suggestionJson
                    .shouldNotBeNull()
            }
        }

    @Test
    fun aDiscardDuringAnInFlightChunkIsNotUndoneByTheChunksWriteBack() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                val jobId = rig.enqueue("a")
                val gate = CompletableDeferred<Unit>()
                rig.ai.gate = gate

                // The drain reads the job and then sits inside the model call.
                val draining = backgroundScope.launch { rig.queue.processNext() }
                while (rig.ai.suggestEditsCalls.isEmpty()) runCurrent()

                // The user discards while the call is in flight; the deleted run must never come
                // back, however the drained chunk's write-back lands.
                val discarding = backgroundScope.launch { rig.queue.discardEditRun(jobId) }
                runCurrent()

                gate.complete(Unit)
                draining.join()
                discarding.join()

                rig.job(jobId) shouldBe null
                rig.rows(jobId) shouldBe emptyList()
            }
        }

    /** What the processing queue shows as running comes from here, never from a run's progress. */
    @Test
    fun aRunIsInFlightWhileItsChunkIsBeingSuggestedAndOnlyThen() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                val jobId = rig.enqueue("a")
                val gate = CompletableDeferred<Unit>()
                rig.ai.gate = gate
                rig.queue.inFlight.value shouldBe null

                val draining = backgroundScope.launch { rig.queue.processNext() }
                while (rig.ai.suggestEditsCalls.isEmpty()) runCurrent()
                rig.queue.inFlight.value shouldBe jobId

                gate.complete(Unit)
                draining.join()
                rig.queue.inFlight.value shouldBe null
            }
        }

    /** The user abandons a run against a slow endpoint; the drain must not make them wait for it. */
    @Test
    fun aDiscardReturnsWhileTheRunsModelCallsAreStillInFlight() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                val jobId = rig.enqueue("a")
                val gate = CompletableDeferred<Unit>()
                rig.ai.gate = gate
                val draining = backgroundScope.launch { rig.queue.processNext() }
                while (rig.ai.suggestEditsCalls.isEmpty()) runCurrent()

                rig.queue.discardEditRun(jobId)

                gate.isCompleted shouldBe false
                rig.job(jobId) shouldBe null
                // The answer that arrives afterwards finds the run gone and writes nothing back.
                gate.complete(Unit)
                draining.join()
                rig.job(jobId) shouldBe null
                rig.rows(jobId) shouldBe emptyList()
            }
        }
}
