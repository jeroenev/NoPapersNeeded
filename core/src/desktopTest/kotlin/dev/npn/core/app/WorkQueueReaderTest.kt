package dev.npn.core.app

import dev.npn.core.db.AiEditJobDocEntity
import dev.npn.core.db.AiEditJobEntity
import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.DeviceDatabase
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.deviceDatabase
import dev.npn.core.db.npnDatabase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** A real index database wired to the reader under test; the two suites below share it. */
internal open class WorkQueueReaderFixture {
    private lateinit var dbDir: File
    protected lateinit var db: NpnDatabase
    protected lateinit var deviceDb: DeviceDatabase
    protected lateinit var reader: WorkQueueReader

    /** Stands in for the live pipeline's `inFlight`; tests set it to name the document being run. */
    protected val ocrInFlight = MutableStateFlow<String?>(null)

    /** Stands in for the live edit queue's `inFlight`; tests set it to name the run being suggested. */
    protected val editInFlight = MutableStateFlow<String?>(null)

    @BeforeTest
    fun setup() {
        dbDir = Files.createTempDirectory("npn-queue-test").toFile()
        db = npnDatabase(File(dbDir, "index.db"))
        deviceDb = deviceDatabase(File(dbDir, "device.db"))
        reader =
            WorkQueueReader(
                db.documentDao(),
                deviceDb.ocrQueueDao(),
                deviceDb.aiEnrichQueueDao(),
                deviceDb.aiEditJobDao(),
                ocrInFlight,
                editInFlight,
            )
    }

    @AfterTest
    fun teardown() {
        db.close()
        deviceDb.close()
        dbDir.deleteRecursively()
    }
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
    ) = db.documentDao().upsert(
        DocumentEntity(
            docId = id,
            relPath = "$id.pdf",
            fileName = "$id.pdf",
            size = 10,
            mtime = 1,
            addedAt = 1,
            title = title,
            trashed = trashed,
            deleted = deleted,
        ),
    )

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
    fun `an empty pair of queues yields nothing`() =
        runTest {
            queue() shouldBe emptyList()
        }

    @Test
    fun `both stages appear, OCR entries before enrichment entries`() =
        runTest {
            putDoc("a")
            putDoc("b")
            putEnrich("b")
            putOcr("a")

            queue().map { it.docId to it.stage } shouldContainExactly
                listOf("a" to WorkStage.OCR, "b" to WorkStage.AI_ENRICHMENT)
        }

    @Test
    fun `one document mid-pipeline appears once per stage`() =
        runTest {
            putDoc("a")
            putOcr("a")
            putEnrich("a")

            queue().map { it.stage } shouldContainExactly
                listOf(WorkStage.OCR, WorkStage.AI_ENRICHMENT)
        }

    @Test
    fun `the document the pipeline is working on right now reads as running`() =
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
    fun `nothing reads as running when no pipeline is working`() =
        runTest {
            // The state a restart used to leave behind: rows owed a pass, nobody working them.
            // Running now comes from the live pipeline, so an idle one cannot report otherwise.
            putDoc("a")
            putOcr("a", attempts = 1)

            queue().single().state shouldBe WorkState.RETRYING
        }

    @Test
    fun `a row left running by an older build is ordinary outstanding work`() =
        runTest {
            // Builds before the in-process rework wrote "running" into the row; nothing could
            // clear one once its process died. It must read as work still owed, not as work
            // forever under way.
            putDoc("a")
            putOcr("a", state = "running")

            queue().single().state shouldBe WorkState.WAITING
        }

    @Test
    fun `a finished OCR row is history, not outstanding work`() =
        runTest {
            putDoc("a")
            putOcr("a", state = QueueState.DONE.column)

            queue() shouldBe emptyList()
        }

    @Test
    fun `a failed OCR row stays visible because it needs attention`() =
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
    fun `a pending OCR row that has already burned attempts reads as a retry`() =
        runTest {
            putDoc("a")
            putOcr("a", state = QueueState.PENDING.column, attempts = 1, nextAttemptAt = 9_000)

            queue().single().state shouldBe WorkState.RETRYING
            queue().single().nextAttemptAt shouldBe 9_000
        }

    @Test
    fun `an enrichment row is waiting until it has failed, then retrying with its backoff`() =
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
    fun `entries are named by title when there is one, else by file name`() =
        runTest {
            putDoc("a", title = "Electricity bill")
            putDoc("b", title = "   ")
            putOcr("a")
            putOcr("b", updatedAt = 2)

            queue().map { it.displayTitle } shouldContainExactly listOf("Electricity bill", "b.pdf")
        }

    @Test
    fun `rows outlive their document, and drop out once it is trashed or deleted`() =
        runTest {
            putDoc("gone-trashed", trashed = true)
            putDoc("gone-deleted", deleted = true)
            putOcr("gone-trashed")
            putEnrich("gone-deleted")
            // A row whose document was never indexed at all must not fabricate an entry either.
            putOcr("never-existed")

            queue() shouldBe emptyList()
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
    fun `an edit run's state is derived from how many of its documents are answered`() =
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
    fun `a half-suggested run nobody is draining is waiting, not running`() =
        runTest {
            putRun("stalled", listOf("a", "b"), suggested = 1)

            runs().single().state shouldBe WorkState.WAITING
        }

    @Test
    fun `a document given up on still counts as answered, so the run can finish`() =
        runTest {
            putRun("run", listOf("a", "b"), suggested = 1, failed = 1)

            val run = runs().single()
            run.state shouldBe WorkState.READY
            run.failures shouldBe 1
            run.total shouldBe 2
        }

    @Test
    fun `finished runs come first, newest first, then the ones still working`() =
        runTest {
            putRun("working", listOf("a"), createdAt = 1)
            putRun("older-ready", listOf("b"), suggested = 1, createdAt = 2, updatedAt = 5)
            putRun("newest-ready", listOf("c"), suggested = 1, createdAt = 3, updatedAt = 7)

            runs().map { it.jobId } shouldContainExactly listOf("newest-ready", "older-ready", "working")
        }

    @Test
    fun `a run with no documents left is not reported at all`() =
        runTest {
            deviceDb.aiEditJobDao().upsertJob(
                AiEditJobEntity(jobId = "empty", requestJson = "{}", createdAt = 1, updatedAt = 1, nextAttemptAt = 1),
            )

            runs() shouldBe emptyList()
        }
}
