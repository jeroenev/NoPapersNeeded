package dev.npn.core.db

import androidx.room3.Room
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WorkQueueTransactionsTest {
    @Test
    fun concurrentOcrRequestsKeepEveryRequestedFlagAndAdvanceTheirStamps() =
        runTest {
            TempIndexDb().use { dbs ->
                val queue = dbs.deviceDb.ocrQueueDao()
                val requests =
                    listOf(
                        async { queue.enqueue("doc", now = 10, rerun = true) },
                        async { queue.enqueue("doc", now = 10, aiRequested = true) },
                        async { queue.enqueue("doc", now = 10) },
                    ).awaitAll()

                requests.map { it.updatedAt }.sorted() shouldContainExactly listOf(10L, 11L, 12L)
                val row = queue.getByDocId("doc")
                row?.rerun shouldBe true
                row?.aiRequested shouldBe true
                row?.updatedAt shouldBe 12L
            }
        }

    @Test
    fun backgroundUpgradeAndStaleAdoptionCannotWithdrawAnExplicitRequest() =
        runTest {
            TempIndexDb().use { dbs ->
                val queue = dbs.deviceDb.ocrQueueDao()
                val seen = OcrQueueEntity("doc", QueueState.DONE.column, updatedAt = 10)
                queue.upsert(seen)
                val request = queue.enqueue("doc", now = 10, aiRequested = true)

                queue.enqueueUpgrade("doc", now = 12, maxAttempts = 3)
                queue.deleteIfUnchanged("doc", seen)

                queue.getByDocId("doc") shouldBe request
                // A running pass whose snapshot was older cannot settle this request either.
                queue.settle("doc", seen) { request.copy(state = QueueState.DONE.column) } shouldBe null
                queue.getByDocId("doc") shouldBe request
            }
        }

    @Test
    fun aBackgroundUpgradeKeepsTheAttemptBudgetAndLeavesFailedRowsAlone() =
        runTest {
            TempIndexDb().use { dbs ->
                val queue = dbs.deviceDb.ocrQueueDao()
                queue.upsert(OcrQueueEntity("done", QueueState.DONE.column, attempts = 2, updatedAt = 10))
                val failed = OcrQueueEntity("failed", QueueState.FAILED.column, attempts = 3, updatedAt = 10)
                queue.upsert(failed)

                queue.enqueueUpgrade("done", now = 20, maxAttempts = 3)
                queue.enqueueUpgrade("failed", now = 20, maxAttempts = 3)

                queue.getByDocId("done")?.attempts shouldBe 2
                queue.getByDocId("done")?.queueState shouldBe QueueState.PENDING
                queue.getByDocId("failed") shouldBe failed
            }
        }

    @Test
    fun anImportUndoRestoresOnlyTheQueueWriteItMade() =
        runTest {
            TempIndexDb().use { dbs ->
                val queue = dbs.deviceDb.ocrQueueDao()
                val previous = OcrQueueEntity("doc", QueueState.FAILED.column, attempts = 3, updatedAt = 10)
                queue.upsert(previous)
                val (before, queued) = queue.enqueueForImport("doc", now = 20)
                before shouldBe previous

                queue.undoEnqueue(queued, before)
                queue.getByDocId("doc") shouldBe previous

                val (old, imported) = queue.enqueueForImport("doc", now = 20)
                val requested = queue.enqueue("doc", now = 20, rerun = true)
                queue.undoEnqueue(imported, old)
                queue.getByDocId("doc") shouldBe requested

                val (absent, fresh) = queue.enqueueForImport("new", now = 20)
                queue.undoEnqueue(fresh, absent)
                queue.getByDocId("new") shouldBe null
            }
        }

    @Test
    fun aFailedDocumentInsertRollsBackItsAiEditJob() = runTest { assertInsertRollsBack(IOException("disk full")) }

    @Test
    fun aCancelledDocumentInsertRollsBackItsAiEditJob() =
        runTest { assertInsertRollsBack(CancellationException("interrupted while inserting documents")) }

    private suspend fun assertInsertRollsBack(failure: Throwable) {
        TempIndexDb().use { dbs ->
            val driver = FailingDocuments(failure)
            val db =
                Room
                    .databaseBuilder<DeviceDatabase>(
                        name = dbs.dir.resolve("fault.db").path,
                    ).setDriver(driver)
                    .build()
            try {
                val queue = db.aiEditJobDao()
                val job = AiEditJobEntity("job", "{}", createdAt = 1, updatedAt = 1, nextAttemptAt = 1)
                driver.armed = true
                val thrown =
                    assertFailsWith<Throwable> {
                        queue.insertRun(job, listOf(AiEditJobDocEntity("job", "doc", position = 0)))
                    }
                thrown shouldBe failure
                driver.armed = false
                queue.job("job") shouldBe null
                queue.docsFor("job") shouldBe emptyList()
            } finally {
                db.close()
            }
        }
    }

    /** An I/O error or cancellation after the job row was inserted but before its documents were. */
    private class FailingDocuments(
        private val failure: Throwable,
        private val delegate: SQLiteDriver = BundledSQLiteDriver(),
    ) : SQLiteDriver by delegate {
        var armed = false

        override fun open(fileName: String): SQLiteConnection {
            val connection = delegate.open(fileName)
            return object : SQLiteConnection by connection {
                override fun prepare(sql: String): SQLiteStatement {
                    val statement = connection.prepare(sql)
                    return object : SQLiteStatement by statement {
                        override fun step(): Boolean {
                            if (armed && sql.startsWith("INSERT") && "ai_edit_job_docs" in sql) throw failure
                            return statement.step()
                        }
                    }
                }
            }
        }
    }
}
