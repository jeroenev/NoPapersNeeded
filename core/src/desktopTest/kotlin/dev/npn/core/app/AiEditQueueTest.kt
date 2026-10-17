@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEditSuggestion
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.db.AiEditJobDocEntity
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.TempIndexDb
import dev.npn.core.index.MutableClock
import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.FakeAiSuggest
import dev.npn.core.testing.FakeDocContentFacade
import dev.npn.core.testing.FakeDocumentRepository
import dev.npn.core.testing.FakeFieldRegistry
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.FakeTagRepository
import dev.npn.core.testing.doc
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import dev.npn.core.work.EndpointHealth
import dev.npn.core.work.backoffMs
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Real device database (the queue owns job and document rows) + the shared AI-edit fakes for the
 * model, the writes and the document text.
 */
private class QueueRig(
    private val dbs: TempIndexDb,
) : AutoCloseable {
    val db: DeviceDatabase = dbs.deviceDb
    val documents = FakeDocumentRepository()
    val meta = FakeMetadataWriter(batchedFieldsOnly = true)
    val content = FakeDocContentFacade()
    val ai = FakeAiSuggest()
    val fieldRegistry = FakeFieldRegistry()

    /** The library's vocabulary: the tags these tests suggest as ones it already has. */
    val tags = FakeTagRepository(listOf("t", "invoice"))
    val clock = MutableClock(start = 1_000L)
    var config: AiConfig = ACTIVE
    private var ids = 0

    /** How often a queue asked for its drain; the session's drainer would run once per kick. */
    var kicks = 0

    /** How many writes a queue handed to the session as owned work, as it must every write a caller drives. */
    var ownedWrites = 0

    /** Thrown by every owned write when set, as a closed device database would. */
    var ownWriteFailure: Throwable? = null

    /** The endpoint's cooldown, as the session's AI facade holds it for every queue. */
    val health = EndpointHealth()

    val queue: AiEditQueue by lazy { newQueue(health) }

    /**
     * A fresh queue over the same database, which is also how "the app was restarted" is
     * expressed: nothing but the job rows carries over, not even the endpoint's cooldown.
     */
    fun newQueue(health: EndpointHealth = EndpointHealth()) =
        AiEditQueue(
            dao = db.aiEditJobDao(),
            documents = documents,
            content = content,
            meta = meta,
            ai = ai,
            fieldRegistry = fieldRegistry,
            tags = tags,
            readConfig = { config },
            clock = clock,
            health = health,
            onEnqueued = { kicks++ },
            ownWrite = { write ->
                ownedWrites++
                ownWriteFailure?.let { throw it }
                write()
            },
            newJobId = { "job-${++ids}" },
        )

    /** Put [docIds] in the library and queue a run over them. */
    suspend fun enqueue(
        vararg docIds: String,
        request: AiEditRequest = AiEditRequest(),
    ): String {
        documents.documents.value = docIds.map { listItem(it) }
        return queue.enqueueEditRun(docIds.toList(), request)
    }

    suspend fun rows(jobId: String): List<AiEditJobDocEntity> = db.aiEditJobDao().docsFor(jobId)

    suspend fun job(jobId: String) = db.aiEditJobDao().job(jobId)

    /**
     * Drain to a standstill, waiting out every cooldown and backoff on the way, which is what the
     * app's kicks amount to over a long enough stretch of wall clock.
     */
    suspend fun drainUntilSettled() {
        repeat(DRAIN_ROUNDS) {
            clock.advance(OUTAGE_STEP_MS)
            while (queue.processNext()) {
                // keep going while the drain can make progress
            }
        }
    }

    override fun close() = dbs.close()
}

/** Longer than the backoff ceiling and the endpoint cooldown, so one step clears both. */
private const val OUTAGE_STEP_MS = 25L * 60L * 60L * 1000L

/** Enough rounds to burn the systemic budget and then the per-document attempt cap. */
private const val DRAIN_ROUNDS = AiEditQueue.MAX_JOB_ATTEMPTS + AiEditQueue.MAX_DOC_ATTEMPTS + 2

private suspend fun TestScope.withQueue(block: suspend (QueueRig) -> Unit) {
    QueueRig(TempIndexDb("npn-edit-queue-db")).use { block(it) }
}

private fun suggestion(
    tag: String = "t",
    title: String? = null,
) = edit(
    addTags = listOf(TagSuggestion(tag, isNew = false)),
    metadata = FieldSuggestion(title?.let { mapOf(FieldKeys.TITLE to it) } ?: emptyMap()),
)

class AiEditQueueSuggestionTest {
    @Test
    fun `a submitted run stores each document once in its first submitted order`() =
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
    fun `a queued run is suggested in submitted order, one chunk per drain step`() =
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
    fun `queueing a run kicks the queue's own drain`() =
        runTest {
            withQueue { rig ->
                rig.enqueue("a")

                rig.kicks shouldBe 1
            }
        }

    @Test
    fun `an unconfigured endpoint does no work at all`() =
        runTest {
            withQueue { rig ->
                rig.config = AiConfig.DISABLED
                rig.enqueue("a")

                rig.queue.processNext() shouldBe false

                rig.ai.suggestEditsCalls shouldBe emptyList()
            }
        }

    @Test
    fun `duplicate documents are queued once, because each one costs a model call`() =
        runTest {
            withQueue { rig ->
                rig.documents.documents.value = listOf(listItem("a"))
                val jobId = rig.queue.enqueueEditRun(listOf("a", "a", "a"), AiEditRequest())

                rig.rows(jobId) shouldHaveSize 1
            }
        }

    @Test
    fun `a document trashed since it was queued is dropped rather than asked about`() =
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
    fun `a run whose every document has gone is dropped rather than stranded`() =
        runTest {
            withQueue { rig ->
                val jobId = rig.enqueue("a")
                rig.documents.documents.value = emptyList()

                rig.queue.processNext() shouldBe true

                rig.job(jobId) shouldBe null
            }
        }

    @Test
    fun `a run survives the process that queued it`() =
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
    fun `a discard during an in-flight chunk is not undone by the chunk's write-back`() =
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
    fun `a run is in flight while its chunk is being suggested, and only then`() =
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
    fun `a discard returns while the run's model calls are still in flight`() =
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

class AiEditQueueFailureTest {
    @Test
    fun `a whole failed chunk is systemic - the run backs off and no document is charged`() =
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
    fun `a systemic failure parks the whole queue, not just the run that hit it`() =
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
    fun `one failing document among successes is charged the attempt, alone`() =
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
    fun `a document failing with a blank message still stores what failed`() =
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
    fun `a run parked by a failure with a blank message still stores what failed`() =
        runTest {
            withQueue { rig ->
                rig.ai.failWith = { error("") }
                val jobId = rig.enqueue("a")

                rig.queue.processNext() shouldBe false

                rig.job(jobId).shouldNotBeNull().lastError shouldBe "IllegalStateException"
            }
        }

    @Test
    fun `a document the endpoint always rejects is given up on, so its run still finishes`() =
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
    fun `an outage inside the systemic budget costs the documents nothing`() =
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
    fun `progress clears a backoff earned while the endpoint was down`() =
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

class AiEditQueueReviewTest {
    private suspend fun QueueRig.suggestAll(jobId: String): String {
        while (queue.processNext()) {
            // drain to a standstill, as the session's kick does
        }
        return jobId
    }

    @Test
    fun `a run still owing model calls does not open`() =
        runTest {
            withQueue { rig ->
                val jobId = rig.enqueue("a")

                rig.queue.openEditRun(jobId) shouldBe null
            }
        }

    @Test
    fun `an unknown run does not open`() =
        runTest {
            withQueue { rig ->
                rig.queue.openEditRun("nope") shouldBe null
            }
        }

    @Test
    fun `the review is projected against the document as it is when it opens, not when it was queued`() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(title = "Electricity bill")
                val jobId = rig.suggestAll(rig.enqueue("a"))

                // Someone titled the document while the run sat in the queue: the suggestion is now
                // an overwrite, which the pre-select policy must leave unticked.
                rig.documents.documents.value = listOf(listItem(doc("a", title = "Already named")))

                val field =
                    rig.queue
                        .openEditRun(jobId)
                        .shouldNotBeNull()
                        .docs
                        .single()
                        .fields
                        .single()
                field.key shouldBe FieldKeys.TITLE
                field.current shouldBe "Already named"
                field.selected shouldBe false
            }
        }

    @Test
    fun `documents the model had nothing for are separated out, never written`() =
        runTest {
            withQueue { rig ->
                rig.ai.editByDoc = mapOf("a" to suggestion(), "b" to AiEditSuggestion.EMPTY)
                val jobId = rig.suggestAll(rig.enqueue("a", "b"))

                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()
                review.docs.map { it.docId } shouldContainExactly listOf("a")
                review.emptyDocs.map { it.docId } shouldContainExactly listOf("b")
            }
        }

    @Test
    fun `applying writes the selection and takes the run out of the queue`() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice", title = "Electricity bill")
                val jobId = rig.suggestAll(rig.enqueue("a"))
                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()

                val outcome = review.apply(review.docs)

                outcome.shouldBeInstanceOf<ApplyOutcome.Done>()
                // A queued run has no scope left to carry on into.
                outcome.result.moreRemain shouldBe false
                rig.meta.addedTags shouldContainExactly listOf(listOf("a") to "invoice")
                rig.meta.batchedEdits.map { it.docId to it.fields } shouldContainExactly
                    listOf("a" to mapOf(FieldKeys.TITLE to "Electricity bill"))
                rig.job(jobId) shouldBe null
                rig.rows(jobId) shouldBe emptyList()
            }
        }

    /**
     * A run reopened after a partly failed Apply (as after a restart, or a queue deletion that
     * failed) offers only what would still change something: the tag that landed is not offered
     * again, so Applying once more does not add it a second time.
     */
    @Test
    fun `reopening a run after a partly failed apply does not offer what already landed`() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice", title = "Bill")
                val jobId = rig.suggestAll(rig.enqueue("a"))
                rig.meta.failApplyEditsForDocs = setOf("a")
                val first = rig.queue.openEditRun(jobId).shouldNotBeNull()
                first.apply(first.docs).shouldBeInstanceOf<ApplyOutcome.Retry>()
                rig.meta.addedTags shouldContainExactly listOf(listOf("a") to "invoice")

                rig.documents.documents.value = listOf(listItem("a", tags = listOf("invoice")))
                rig.meta.failApplyEditsForDocs = emptySet()
                val reopened = rig.queue.openEditRun(jobId).shouldNotBeNull()

                reopened.docs
                    .single()
                    .chips
                    .filter { it.selected }
                    .map { it.tag } shouldBe emptyList()
                reopened.apply(reopened.docs).shouldBeInstanceOf<ApplyOutcome.Done>()
                rig.meta.addedTags shouldContainExactly listOf(listOf("a") to "invoice")
                rig.meta.batchedEdits.map { it.docId to it.fields } shouldContainExactly
                    listOf("a" to mapOf(FieldKeys.TITLE to "Bill"))
            }
        }

    /**
     * Discarding a run after an Apply that partly landed leaves those writes standing, so the Done
     * it ends in reports them rather than "no documents were changed".
     */
    @Test
    fun `a run discarded after a partly failed apply reports what landed`() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice", title = "Bill")
                val jobId = rig.suggestAll(rig.enqueue("a"))
                rig.meta.failApplyEditsForDocs = setOf("a")
                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()
                review.landedSoFar() shouldBe ApplyResult(applied = 0, docsChanged = 0, moreRemain = false)
                review.apply(review.docs).shouldBeInstanceOf<ApplyOutcome.Retry>()

                review.discard()

                review.landedSoFar() shouldBe ApplyResult(applied = 1, docsChanged = 1, moreRemain = false)
                rig.job(jobId) shouldBe null
            }
        }

    /**
     * Whether a tag is new is judged when the run opens: since it was suggested, the library may
     * have dropped a tag it had or gained one the model invented, in its own spelling.
     */
    @Test
    fun `a suggested tag is new or known by the vocabulary when the run opens`() =
        runTest {
            withQueue { rig ->
                val known = TagSuggestion("invoice", isNew = false)
                rig.ai.edit = edit(addTags = listOf(known, TagSuggestion("energy", isNew = true)))
                val jobId = rig.suggestAll(rig.enqueue("a"))
                rig.tags.tagNames = listOf("Energy")

                rig.queue
                    .openEditRun(jobId)
                    .shouldNotBeNull()
                    .docs
                    .single()
                    .chips shouldContainExactly
                    listOf(
                        ReviewChip("invoice", isNew = true, selected = false),
                        ReviewChip("Energy", isNew = false, selected = true),
                    )
            }
        }

    /** A removal of a tag the document no longer carries, or a value it already has, changes nothing. */
    @Test
    fun `a review leaves out removals and values that would change nothing`() =
        runTest {
            withQueue { rig ->
                rig.ai.edit =
                    edit(
                        removeTags = listOf("draft", "old"),
                        metadata = FieldSuggestion(mapOf(FieldKeys.TITLE to "Bill", FieldKeys.CORRESPONDENT to "Acme")),
                    )
                val jobId = rig.suggestAll(rig.enqueue("a"))
                rig.documents.documents.value = listOf(listItem(doc("a", title = "Bill"), tags = listOf("Draft")))

                val row =
                    rig.queue
                        .openEditRun(jobId)
                        .shouldNotBeNull()
                        .docs
                        .single()

                row.removals.map { it.tag } shouldContainExactly listOf("Draft")
                row.fields.map { it.key } shouldContainExactly listOf(FieldKeys.CORRESPONDENT)
            }
        }

    @Test
    fun `a partly failed apply keeps the run so the retry can finish it`() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice")
                rig.meta.failAddTagForDocs = setOf("a")
                val jobId = rig.suggestAll(rig.enqueue("a"))
                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()

                review.apply(review.docs).shouldBeInstanceOf<ApplyOutcome.Retry>()

                rig.job(jobId).shouldNotBeNull()
            }
        }

    /**
     * Applying and discarding are caller-driven, like queueing: the session must be able to wait for
     * their writes before it closes the device database. The drain's own writes run on the session's
     * drain and are not counted here.
     */
    @Test
    fun `finishing a review deletes the run as owned work, applied or discarded`() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice")
                val applied = rig.suggestAll(rig.enqueue("a"))
                val review = rig.queue.openEditRun(applied).shouldNotBeNull()
                review.apply(review.docs).shouldBeInstanceOf<ApplyOutcome.Done>()
                val discarded = rig.suggestAll(rig.enqueue("b"))
                rig.queue
                    .openEditRun(discarded)
                    .shouldNotBeNull()
                    .discard()

                rig.job(applied) shouldBe null
                rig.job(discarded) shouldBe null
                // Two runs queued, two finished.
                rig.ownedWrites shouldBe 4
            }
        }

    /**
     * Dropping the run from the queue comes after the writes; when it fails, the writes still stand,
     * so the review is done, with the failure beside its counts, and the run stays queued.
     */
    @Test
    fun `an applied run that cannot leave the queue is still done`() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion(tag = "invoice")
                val jobId = rig.suggestAll(rig.enqueue("a"))
                val review = rig.queue.openEditRun(jobId).shouldNotBeNull()
                val failure = IllegalStateException("database closed")
                rig.ownWriteFailure = failure

                val done = review.apply(review.docs).shouldBeInstanceOf<ApplyOutcome.Done>()

                done.result shouldBe ApplyResult(applied = 1, docsChanged = 1, moreRemain = false)
                done.followUpFailure shouldBe failure
                rig.meta.addedTags shouldContainExactly listOf(listOf("a") to "invoice")
                rig.job(jobId).shouldNotBeNull()
            }
        }

    @Test
    fun `discarding drops the run and its suggestions`() =
        runTest {
            withQueue { rig ->
                rig.ai.edit = suggestion()
                val jobId = rig.suggestAll(rig.enqueue("a"))

                rig.queue.discardEditRun(jobId)

                rig.job(jobId) shouldBe null
                rig.rows(jobId) shouldBe emptyList()
                rig.queue.openEditRun(jobId) shouldBe null
            }
        }
}
