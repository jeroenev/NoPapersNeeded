package dev.npn.core.app

import dev.npn.core.db.AiEditJobDocEntity
import dev.npn.core.db.AiEditJobEntity
import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.TempIndexDb
import dev.npn.core.db.documentEntity
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/** A real index database wired to the reader under test; the two suites below share it. */
internal open class WorkQueueReaderFixture {
    private val temp = TempIndexDb("npn-queue-test")
    protected val db: NpnDatabase = temp.db
    protected val deviceDb: DeviceDatabase = temp.deviceDb

    /** Stands in for the live pipeline's `inFlight`; tests set it to name the document being run. */
    protected val ocrInFlight = MutableStateFlow<String?>(null)

    /** Stands in for the live edit queue's `inFlight`; tests set it to name the run being suggested. */
    protected val editInFlight = MutableStateFlow<String?>(null)

    protected val reader =
        WorkQueueReader(
            db.documentDao(),
            deviceDb.ocrQueueDao(),
            deviceDb.aiEnrichQueueDao(),
            deviceDb.aiEditJobDao(),
            ocrInFlight,
            editInFlight,
            db.invalidationTracker.createFlow("documents"),
        )

    @AfterTest
    fun teardown() = temp.close()
}

/**
 * The per-document half of the read seam: which rows are outstanding, how each table's notion of
 * failure maps onto [WorkState], and what happens to rows whose document has gone away.
 */
internal class WorkQueueReaderTest : WorkQueueReaderFixture() {
    private suspend fun putDoc(
        id: String,
        title: String? = null,
        trashed: Boolean = false,
        deleted: Boolean = false,
    ) = db.documentDao().upsert(documentEntity(id, title = title, trashed = trashed, deleted = deleted))

    private suspend fun putOcr(
        id: String,
        state: String = QueueState.PENDING.column,
        attempts: Int = 0,
        updatedAt: Long = 1,
        lastError: String? = null,
        nextAttemptAt: Long = 0,
    ) = deviceDb.ocrQueueDao().upsert(
        OcrQueueEntity(id, state, attempts, updatedAt, lastError, nextAttemptAt = nextAttemptAt),
    )

    private suspend fun putEnrich(
        id: String,
        attempts: Int = 0,
        nextAttemptAt: Long = 1,
        lastError: String? = null,
    ) = deviceDb.aiEnrichQueueDao().upsert(AiEnrichQueueEntity(id, attempts, nextAttemptAt, 1, lastError))

    private suspend fun queue(): List<QueuedWork> = reader.queue.first()

    @Test
    fun anEmptyPairOfQueuesYieldsNothing() =
        runTest {
            queue() shouldBe emptyList()
        }

    @Test
    fun bothStagesAppearOCREntriesBeforeEnrichmentEntries() =
        runTest {
            putDoc("a")
            putDoc("b")
            putEnrich("b")
            putOcr("a")

            queue().map { it.docId to it.stage } shouldContainExactly
                listOf("a" to WorkStage.OCR, "b" to WorkStage.AI_ENRICHMENT)
        }

    @Test
    fun oneDocumentMidPipelineAppearsOncePerStage() =
        runTest {
            putDoc("a")
            putOcr("a")
            putEnrich("a")

            queue().map { it.stage } shouldContainExactly
                listOf(WorkStage.OCR, WorkStage.AI_ENRICHMENT)
        }

    @Test
    fun theDocumentThePipelineIsWorkingOnRightNowReadsAsRunning() =
        runTest {
            putDoc("a")
            putDoc("b")
            putOcr("a")
            putOcr("b", updatedAt = 2)
            ocrInFlight.value = "a"

            queue().associate { it.docId to it.state } shouldBe
                mapOf("a" to WorkState.RUNNING, "b" to WorkState.WAITING)
        }

    @Test
    fun nothingReadsAsRunningWhenNoPipelineIsWorking() =
        runTest {
            // The state a restart used to leave behind: rows owed a pass, nobody working them.
            // Running now comes from the live pipeline, so an idle one cannot report otherwise.
            putDoc("a")
            putOcr("a", attempts = 1)

            queue().single().state shouldBe WorkState.RETRYING
        }

    @Test
    fun aRowLeftRunningByAnOlderBuildIsOrdinaryOutstandingWork() =
        runTest {
            // Builds before the in-process rework wrote "running" into the row; nothing could
            // clear one once its process died. It must read as work still owed, not as work
            // forever under way.
            putDoc("a")
            putOcr("a", state = "running")

            queue().single().state shouldBe WorkState.WAITING
        }

    @Test
    fun aFinishedOCRRowIsHistoryNotOutstandingWork() =
        runTest {
            putDoc("a")
            putOcr("a", state = QueueState.DONE.column)

            queue() shouldBe emptyList()
        }

    @Test
    fun aFailedOCRRowStaysVisibleBecauseItNeedsAttention() =
        runTest {
            putDoc("a")
            putOcr("a", state = QueueState.FAILED.column, attempts = 3, lastError = "boom", nextAttemptAt = 9_000)

            val entry = queue().single()
            entry.state shouldBe WorkState.FAILED
            entry.attempts shouldBe 3
            entry.lastError shouldBe "boom"
            // Given up on: no retry is coming, whatever the row's last backoff said.
            entry.nextAttemptAt shouldBe null
        }

    @Test
    fun aPendingOCRRowThatHasAlreadyBurnedAttemptsReadsAsARetry() =
        runTest {
            putDoc("a")
            putOcr("a", state = QueueState.PENDING.column, attempts = 1, nextAttemptAt = 9_000)

            queue().single().state shouldBe WorkState.RETRYING
            queue().single().nextAttemptAt shouldBe 9_000
        }

    @Test
    fun anEnrichmentRowIsWaitingUntilItHasFailedThenRetryingWithItsBackoff() =
        runTest {
            putDoc("a")
            putDoc("b")
            putEnrich("a")
            putEnrich("b", attempts = 2, nextAttemptAt = 9_000, lastError = "429")

            val byId = queue().associateBy { it.docId }
            byId.getValue("a").state shouldBe WorkState.WAITING
            byId.getValue("a").nextAttemptAt shouldBe 1
            byId.getValue("b").state shouldBe WorkState.RETRYING
            byId.getValue("b").nextAttemptAt shouldBe 9_000
            byId.getValue("b").lastError shouldBe "429"
        }

    @Test
    fun entriesAreNamedByTitleWhenThereIsOneElseByFileName() =
        runTest {
            putDoc("a", title = "Electricity bill")
            putDoc("b", title = "   ")
            putOcr("a")
            putOcr("b", updatedAt = 2)

            queue().map { it.displayTitle } shouldContainExactly listOf("Electricity bill", "b.pdf")
        }

    @Test
    fun rowsOutliveTheirDocumentAndDropOutOnceItIsTrashedOrDeleted() =
        runTest {
            putDoc("gone-trashed", trashed = true)
            putDoc("gone-deleted", deleted = true)
            putOcr("gone-trashed")
            putEnrich("gone-deleted")
            // A row whose document was never indexed at all must not fabricate an entry either.
            putOcr("never-existed")

            queue() shouldBe emptyList()
        }

    /**
     * An open subscription follows its rows' documents: a new title renames a row, a trash drops it.
     * The database notifies from its own threads, so the wait is bounded in real time.
     */
    @Test
    fun aSubscribedQueueFollowsADocumentRetitledAndThenTrashed() =
        runTest(timeout = 10.seconds) {
            putDoc("a")
            putOcr("a", state = QueueState.FAILED.column, attempts = 3)
            val latest = MutableStateFlow<List<QueuedWork>?>(null)
            backgroundScope.launch { reader.queue.collect { latest.value = it } }
            latest.first { it?.singleOrNull()?.displayTitle == "a.pdf" }

            putDoc("a", title = "Electricity bill")
            latest.first { it?.singleOrNull()?.displayTitle == "Electricity bill" }

            putDoc("a", title = "Electricity bill", trashed = true)
            latest.first { it?.isEmpty() == true }
        }
}

/**
 * The whole-run half of the read seam: a queued edit run's state and ordering fall out of its
 * document rows rather than a stored column, so the two can never disagree.
 */
internal class EditRunReaderTest : WorkQueueReaderFixture() {
    private suspend fun putRun(
        jobId: String,
        docIds: List<String>,
        suggested: Int = 0,
        failed: Int = 0,
        attempts: Int = 0,
        createdAt: Long = 1,
        updatedAt: Long = 1,
    ) {
        deviceDb.aiEditJobDao().upsertJob(
            AiEditJobEntity(
                jobId = jobId,
                requestJson = "{}",
                createdAt = createdAt,
                updatedAt = updatedAt,
                attempts = attempts,
                nextAttemptAt = 9_000,
            ),
        )
        deviceDb.aiEditJobDao().upsertDocs(
            docIds.mapIndexed { index, docId ->
                AiEditJobDocEntity(
                    jobId = jobId,
                    docId = docId,
                    position = index,
                    suggestionJson = "{}".takeIf { index < suggested },
                    failed = index >= docIds.size - failed,
                )
            },
        )
    }

    private suspend fun runs(): List<QueuedEditRun> = reader.editRuns.first()

    @Test
    fun anEditRunsStateIsDerivedFromHowManyOfItsDocumentsAreAnswered() =
        runTest {
            putRun("untouched", listOf("a", "b", "c"))
            putRun("underway", listOf("d", "e", "f"), suggested = 1)
            putRun("backing-off", listOf("g", "h"), attempts = 2)
            putRun("finished", listOf("i", "j"), suggested = 2)
            editInFlight.value = "underway"

            val byId = runs().associateBy { it.jobId }
            byId.getValue("untouched").state shouldBe WorkState.WAITING
            byId.getValue("underway").state shouldBe WorkState.RUNNING
            byId.getValue("underway").done shouldBe 1
            byId.getValue("backing-off").state shouldBe WorkState.RETRYING
            byId.getValue("backing-off").nextAttemptAt shouldBe 9_000
            byId.getValue("finished").state shouldBe WorkState.READY
        }

    /**
     * Progress says only that answers were bought, not that anyone is buying more: a half-done run
     * whose endpoint was removed must not show as running for ever.
     */
    @Test
    fun aHalfSuggestedRunNobodyIsDrainingIsWaitingNotRunning() =
        runTest {
            putRun("stalled", listOf("a", "b"), suggested = 1)

            runs().single().state shouldBe WorkState.WAITING
        }

    @Test
    fun aDocumentGivenUpOnStillCountsAsAnsweredSoTheRunCanFinish() =
        runTest {
            putRun("run", listOf("a", "b"), suggested = 1, failed = 1)

            val run = runs().single()
            run.state shouldBe WorkState.READY
            run.failures shouldBe 1
            run.total shouldBe 2
        }

    @Test
    fun finishedRunsComeFirstNewestFirstThenTheOnesStillWorking() =
        runTest {
            putRun("working", listOf("a"), createdAt = 1)
            putRun("older-ready", listOf("b"), suggested = 1, createdAt = 2, updatedAt = 5)
            putRun("newest-ready", listOf("c"), suggested = 1, createdAt = 3, updatedAt = 7)

            runs().map { it.jobId } shouldContainExactly listOf("newest-ready", "older-ready", "working")
        }

    @Test
    fun aRunWithNoDocumentsLeftIsNotReportedAtAll() =
        runTest {
            deviceDb.aiEditJobDao().upsertJob(
                AiEditJobEntity(jobId = "empty", requestJson = "{}", createdAt = 1, updatedAt = 1, nextAttemptAt = 1),
            )

            runs() shouldBe emptyList()
        }
}
