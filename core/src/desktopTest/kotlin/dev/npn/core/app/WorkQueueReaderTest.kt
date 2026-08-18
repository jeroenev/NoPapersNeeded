package dev.npn.core.app

import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.DocumentEntity
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.npnDatabase
import dev.npn.core.ocr.OcrPipeline
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Covers the read seam that turns the two unlike queue tables into one [QueuedWork] list: which
 * rows are outstanding, how each table's notion of failure maps onto [WorkState], and what happens
 * to rows whose document has gone away.
 */
class WorkQueueReaderTest {
    private lateinit var dbDir: File
    private lateinit var db: NpnDatabase
    private lateinit var reader: WorkQueueReader

    @BeforeTest
    fun setup() {
        dbDir = Files.createTempDirectory("npn-queue-test").toFile()
        db = npnDatabase(File(dbDir, "index.db"))
        reader = WorkQueueReader(db.documentDao(), db.ocrQueueDao(), db.aiEnrichQueueDao())
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbDir.deleteRecursively()
    }

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
        state: String = OcrPipeline.STATE_PENDING,
        attempts: Int = 0,
        updatedAt: Long = 1,
        lastError: String? = null,
    ) = db.ocrQueueDao().upsert(OcrQueueEntity(id, state, attempts, updatedAt, lastError))

    private suspend fun putEnrich(
        id: String,
        attempts: Int = 0,
        nextAttemptAt: Long = 1,
        lastError: String? = null,
    ) = db.aiEnrichQueueDao().upsert(AiEnrichQueueEntity(id, attempts, nextAttemptAt, 1, lastError))

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
            putOcr("a", state = OcrPipeline.STATE_RUNNING)
            putEnrich("a")

            queue().map { it.stage } shouldContainExactly
                listOf(WorkStage.OCR, WorkStage.AI_ENRICHMENT)
        }

    @Test
    fun `a finished OCR row is history, not outstanding work`() =
        runTest {
            putDoc("a")
            putOcr("a", state = OcrPipeline.STATE_DONE)

            queue() shouldBe emptyList()
        }

    @Test
    fun `a failed OCR row stays visible because it needs attention`() =
        runTest {
            putDoc("a")
            putOcr("a", state = OcrPipeline.STATE_FAILED, attempts = 3, lastError = "boom")

            val entry = queue().single()
            entry.state shouldBe WorkState.FAILED
            entry.attempts shouldBe 3
            entry.lastError shouldBe "boom"
        }

    @Test
    fun `a pending OCR row that has already burned attempts reads as a retry`() =
        runTest {
            putDoc("a")
            putOcr("a", state = OcrPipeline.STATE_PENDING, attempts = 1)

            queue().single().state shouldBe WorkState.RETRYING
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
