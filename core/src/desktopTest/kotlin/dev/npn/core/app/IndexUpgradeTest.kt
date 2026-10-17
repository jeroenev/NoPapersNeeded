package dev.npn.core.app

import androidx.room3.useReaderConnection
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.FakeSseGateway
import dev.npn.core.db.AiEditJobDocEntity
import dev.npn.core.db.AiEditJobEntity
import dev.npn.core.db.AiEnrichQueueEntity
import dev.npn.core.db.LegacyIndexTables
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.OcrPageCacheEntity
import dev.npn.core.db.OcrQueueEntity
import dev.npn.core.db.QueueState
import dev.npn.core.db.createFromSchema
import dev.npn.core.db.exec
import dev.npn.core.db.queueState
import dev.npn.core.fs.FileMeta
import dev.npn.core.fs.LibraryFileSystem
import dev.npn.core.index.DeviceIdentity
import dev.npn.core.ocr.OcrEngineId
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.NpnJson
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Opening a library over the index an older build left behind: what the index held that it cannot
 * rebuild reaches its new home before the index forgets it.
 */
class IndexUpgradeTest {
    private val dirs = LibraryDirs()
    private val library = dirs.library
    private val data = dirs.data

    @AfterTest
    fun teardown() = dirs.close()

    @Test
    fun savedViewsInTheIndexMoveIntoTheJournal() =
        runTest {
            val view = SavedView("v1", "Invoices", LibraryFilter(tagsAll = listOf("invoice")), pinned = true)
            createFromSchema(File(data, "index.db"), version = 22) { connection ->
                connection.exec(
                    "INSERT INTO saved_views (id, name, json, pinned, position) VALUES (?, ?, ?, 1, 0)",
                    view.id,
                    view.name,
                    NpnJson.Persisted.encodeToString(view),
                )
            }

            openSession(backgroundScope, library, data).use { session, deps ->
                session.savedViews.observeSavedViews().first() shouldBe listOf(view)
                LegacyIndexTables.savedViews(deps.db) shouldBe null
            }
            // Library data now: a device with an index of its own reads them from the journal.
            openSession(backgroundScope, library, File(data, "other-device")).use { session, _ ->
                session.savedViews.observeSavedViews().first() shouldBe listOf(view)
            }
        }

    /**
     * What an index from before device.db held for this device — the device id, queued edit runs,
     * the enrichment queue and its marks, the OCR queue with a paid-for request and pages, chats —
     * reaches the device database, and the index keeps none of it. The v1 writer's state, which
     * nothing reads any more, is dropped from both.
     */
    @Test
    fun deviceStateInTheIndexMovesToTheDeviceDatabase() =
        runTest {
            val doc = "a".repeat(64)
            createFromSchema(File(data, "index.db"), version = 22) { connection ->
                connection.exec(
                    "INSERT INTO documents (docId, relPath, fileName, size, mtime, addedAt, reviewed, trashed, " +
                        "deleted, missing, ocrState, aiEnrichedAt) " +
                        "VALUES (?, 'a.pdf', 'a.pdf', 1, 1, 1, 0, 0, 0, 0, 'done', 42)",
                    doc,
                )
                for ((key, value) in LEGACY_KV) connection.exec("INSERT INTO kv (key, value) VALUES (?, ?)", key, value)
                connection.exec(
                    "INSERT INTO ai_edit_jobs (jobId, requestJson, createdAt, updatedAt, attempts, nextAttemptAt) " +
                        "VALUES ('job-1', '{}', 1, 2, 0, 0)",
                )
                connection.exec(
                    "INSERT INTO ai_edit_job_docs (jobId, docId, position, suggestionJson, attempts, failed) " +
                        "VALUES ('job-1', ?, 0, '{\"paid\":true}', 0, 0)",
                    doc,
                )
                connection.exec(
                    "INSERT INTO ai_enrich_queue (docId, attempts, nextAttemptAt, updatedAt) VALUES (?, 1, 9, 3)",
                    "b".repeat(64),
                )
                connection.exec(
                    "INSERT INTO ocr_queue (docId, state, attempts, updatedAt, rerun, aiRequested, nextAttemptAt) " +
                        "VALUES (?, 'pending', 1, 4, 0, 1, 5)",
                    doc,
                )
                connection.exec(
                    "INSERT INTO ocr_page_cache (docId, engine, lang, page, text) " +
                        "VALUES (?, 'ai', 'eng', 0, 'paid page')",
                    doc,
                )
            }

            openSession(backgroundScope, library, data).use { session, deps ->
                session.deviceId shouldBe "device-from-the-index"
                val device = deps.deviceDb
                device.keyValueDao().getAll(LEGACY_KV.keys.toList()) shouldBe
                    LEGACY_KV - OBSERVATION_KEY - DeviceIdentity.LEGACY_JOURNAL_KEYS.toSet()
                device
                    .aiEditJobDao()
                    .docsFor("job-1")
                    .single()
                    .suggestionJson shouldBe "{\"paid\":true}"
                session.work.editRuns
                    .first()
                    .single()
                    .jobId shouldBe "job-1"
                device.aiEnrichQueueDao().getByDocId("b".repeat(64))?.attempts shouldBe 1
                device.aiEnrichQueueDao().enrichedAt(doc) shouldBe 42
                device.ocrQueueDao().getByDocId(doc)?.aiRequested shouldBe true
                device
                    .ocrQueueDao()
                    .cachedPages(doc, "ai", "eng")
                    .single()
                    .text shouldBe "paid page"

                // The index keeps only what it can rebuild: its own observations, not the moved state.
                deps.db.keyValueDao().getAll(LEGACY_KV.keys.toList()) shouldBe mapOf(OBSERVATION_KEY to "seen")
                deps.db.tableNames() shouldNotContainAnyOf MOVED_TABLES
                deps.db.columnNames("documents") shouldNotContain "aiEnrichedAt"
            }
        }

    /**
     * Once device.db exists it is the authority: an older index put back beside it brings back
     * neither an id this installation gave up, nor an older chat transcript or edit run in place of
     * the ones the device holds now. What device.db lacks still moves, and the index keeps none of it.
     */
    @Test
    fun anOlderIndexBesideTheDeviceDatabaseOverwritesNothing() =
        runTest {
            var deviceId = ""
            openSession(backgroundScope, library, data).use { session, deps ->
                deviceId = session.deviceId
                deps.deviceDb.keyValueDao().put("ai-chat:c1", "[\"newer\"]")
                deps.deviceDb.aiEditJobDao().upsertJob(
                    AiEditJobEntity("job-1", requestJson = "{}", createdAt = 1, updatedAt = 7, nextAttemptAt = 0),
                )
                deps.deviceDb.aiEditJobDao().upsertDocs(
                    listOf(
                        AiEditJobDocEntity("job-1", "a".repeat(64), position = 0, suggestionJson = "{\"paid\":true}"),
                    ),
                )
            }

            data.listFiles { file -> file.name.startsWith("index.db") }!!.forEach { it.delete() }
            createFromSchema(File(data, "index.db"), version = 22) { connection ->
                for ((key, value) in LEGACY_KV) connection.exec("INSERT INTO kv (key, value) VALUES (?, ?)", key, value)
                connection.exec(
                    "INSERT INTO ai_edit_jobs (jobId, requestJson, createdAt, updatedAt, attempts, nextAttemptAt) " +
                        "VALUES ('job-1', '{}', 1, 2, 0, 0), ('job-2', '{}', 1, 2, 0, 0)",
                )
                connection.exec(
                    "INSERT INTO ai_edit_job_docs (jobId, docId, position, suggestionJson, attempts, failed) " +
                        "VALUES ('job-1', ?, 0, NULL, 0, 0), ('job-1', ?, 1, NULL, 0, 0), ('job-2', ?, 0, NULL, 0, 0)",
                    "a".repeat(64),
                    "b".repeat(64),
                    "c".repeat(64),
                )
            }

            openSession(backgroundScope, library, data).use { session, deps ->
                session.deviceId shouldBe deviceId
                val device = deps.deviceDb
                device.keyValueDao().get("device.id") shouldBe deviceId
                device.keyValueDao().get("ai-chat:c1") shouldBe "[\"newer\"]"
                device.keyValueDao().get("ai-chat:session-metadata") shouldBe "[]"
                device.aiEditJobDao().job("job-1")?.updatedAt shouldBe 7
                device.aiEditJobDao().docsFor("job-1").map { it.suggestionJson } shouldBe listOf("{\"paid\":true}")
                device.aiEditJobDao().docsFor("job-2").map { it.docId } shouldBe listOf("c".repeat(64))

                deps.db.keyValueDao().getAll(LEGACY_KV.keys.toList()) shouldBe mapOf(OBSERVATION_KEY to "seen")
                deps.db.tableNames() shouldNotContainAnyOf MOVED_TABLES
            }
        }

    /** The index is a cache: deleting it costs a rebuild, never this device's own state. */
    @Test
    fun aDroppedIndexRebuildsAndTheDeviceStateSurvives() =
        runTest {
            library.resolve("a.pdf").toFile().writeBytes(PdfFixtures.embeddedTextPdf("alpha"))
            var deviceId = ""
            var docId = ""
            openSession(backgroundScope, library, data).use { session, deps ->
                session.rescan()
                deviceId = session.deviceId
                docId = deps.fs.sha256("a.pdf")
                deps.deviceDb.aiEditJobDao().upsertJob(
                    AiEditJobEntity("job-1", requestJson = "{}", createdAt = 1, updatedAt = 1, nextAttemptAt = 0),
                )
                deps.deviceDb.aiEditJobDao().upsertDocs(listOf(AiEditJobDocEntity("job-1", docId, position = 0)))
                val queued =
                    deps.deviceDb
                        .ocrQueueDao()
                        .getByDocId(docId)
                        .shouldNotBeNull()
                deps.deviceDb.ocrQueueDao().upsert(queued.copy(attempts = 2, aiRequested = true))
            }

            data.listFiles { file -> file.name.startsWith("index.db") }!!.forEach { it.delete() }

            openSession(backgroundScope, library, data).use { session, deps ->
                session.rescan()
                session.deviceId shouldBe deviceId
                session.documents
                    .observeDoc(docId)
                    .first()
                    .shouldNotBeNull()
                session.work.editRuns
                    .first()
                    .single()
                    .jobId shouldBe "job-1"
                // The run this device still owes the document keeps its attempts and its request.
                val owed =
                    deps.deviceDb
                        .ocrQueueDao()
                        .getByDocId(docId)
                        .shouldNotBeNull()
                owed.attempts shouldBe 2
                owed.aiRequested shouldBe true
            }
        }

    /**
     * Opened over a dropped index, the session's queue drains wait for its first rescan: until it has
     * listed the library, a document with no row is one the index does not know yet, not one that is
     * gone. So this device's queued work outlives the wait, and then runs as it would have; a row for
     * a document that really is gone is still dropped.
     */
    @Test
    fun theQueuesWaitForTheFirstRescanOfARebuiltIndex() = queuesWaitForRescan(initialListingFails = false)

    @Test
    fun theQueuesSurviveAFailedStartupRescanUntilARetrySucceeds() = queuesWaitForRescan(initialListingFails = true)

    private fun queuesWaitForRescan(initialListingFails: Boolean) =
        runTest {
            val gateway = FakeSseGateway("").start()
            val endpoint =
                AiConfig(
                    enabled = true,
                    provider = AiProvider.CUSTOM,
                    baseUrl = gateway.baseUrl,
                    model = "test-model",
                    autoOnOcr = true,
                )
            library.resolve("a.pdf").toFile().writeBytes(PdfFixtures.embeddedTextPdf("alpha"))
            val gone = "f".repeat(64)
            var docId = ""
            openSession(backgroundScope, library, data).use { session, deps ->
                session.rescan()
                session.work.drainOcr()
                docId = deps.fs.sha256("a.pdf")
                val device = deps.deviceDb
                // Due work this device owes: a paid-for AI run cut short, an enrichment, an edit run.
                device.ocrQueueDao().upsert(
                    OcrQueueEntity(docId, QueueState.PENDING.column, attempts = 2, updatedAt = 1, aiRequested = true),
                )
                device.ocrQueueDao().cachePage(OcrPageCacheEntity(docId, OcrEngineId.AI.column, "eng", 0, "paid page"))
                device.ocrQueueDao().upsert(OcrQueueEntity(gone, QueueState.PENDING.column, updatedAt = 1))
                device.aiEnrichQueueDao().upsert(AiEnrichQueueEntity(docId, nextAttemptAt = 0, updatedAt = 1))
                device.aiEditJobDao().upsertJob(
                    AiEditJobEntity("job-1", requestJson = "{}", createdAt = 1, updatedAt = 1, nextAttemptAt = 0),
                )
                device.aiEditJobDao().upsertDocs(listOf(AiEditJobDocEntity("job-1", docId, position = 0)))
            }

            data.listFiles { file -> file.name.startsWith("index.db") }.orEmpty().forEach { it.delete() }

            val listing = HeldListing(failFirst = initialListingFails)
            try {
                val reopened =
                    openSession(backgroundScope, library, data, aiConfig = { endpoint }, files = listing::over)
                reopened.use { session, deps ->
                    val device = deps.deviceDb
                    session.start()
                    val retry =
                        if (initialListingFails) {
                            listing.failed.await()
                            backgroundScope.launch { session.rescan() }
                        } else {
                            null
                        }
                    listing.reached.await()

                    // Every drain is asked for while the rescan is held, and none gets anywhere.
                    val drains =
                        backgroundScope.launch {
                            session.work.drainOcr()
                            session.work.drainEnrichment()
                            session.work.drainEditRuns()
                        }
                    listing.finishesWithin(drains, millis = 1_000) shouldBe false
                    val owed = device.ocrQueueDao().getByDocId(docId).shouldNotBeNull()
                    owed.aiRequested shouldBe true
                    owed.attempts shouldBe 2
                    device
                        .ocrQueueDao()
                        .cachedPages(docId, OcrEngineId.AI.column, "eng")
                        .single()
                        .text shouldBe "paid page"
                    device.ocrQueueDao().getByDocId(gone).shouldNotBeNull()
                    device.aiEnrichQueueDao().getByDocId(docId).shouldNotBeNull()
                    device.aiEditJobDao().docsFor("job-1").map { it.docId } shouldBe listOf(docId)
                    gateway.requestedPaths.shouldBeEmpty()

                    listing.release()
                    drains.join()
                    retry?.join()

                    device.ocrQueueDao().getByDocId(docId)?.queueState shouldBe QueueState.DONE
                    device.ocrQueueDao().getByDocId(gone) shouldBe null
                    // The endpoint fails every call, so the work is tried and stays owed.
                    gateway.requestedPaths.shouldNotBeEmpty()
                    device.aiEnrichQueueDao().getByDocId(docId).shouldNotBeNull()
                    device.aiEditJobDao().docsFor("job-1").map { it.docId } shouldBe listOf(docId)
                }
            } finally {
                listing.release()
                gateway.stop()
            }
        }

    private suspend fun NpnDatabase.tableNames(): List<String> =
        useReaderConnection { connection ->
            connection.usePrepared("SELECT name FROM sqlite_master WHERE type = 'table'") { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }

    private suspend fun NpnDatabase.columnNames(table: String): List<String> =
        useReaderConnection { connection ->
            connection.usePrepared("SELECT name FROM pragma_table_info('$table')") { statement ->
                buildList { while (statement.step()) add(statement.getText(0)) }
            }
        }

    private companion object {
        const val OBSERVATION_KEY = "observed-document-path:x"

        val LEGACY_KV =
            mapOf(
                "device.id" to "device-from-the-index",
                "journal.lastSeq" to "5",
                "journal.knownOwnSegments" to "[]",
                "ai-chat:session-metadata" to "[]",
                "ai-chat:c1" to "[]",
                OBSERVATION_KEY to "seen",
            )

        val MOVED_TABLES =
            listOf("ai_edit_jobs", "ai_edit_job_docs", "ai_enrich_queue", "ai_enriched", "ocr_queue", "ocr_page_cache")
    }
}

/**
 * A library filesystem whose rescan listing pauses at its gate: a rescan still listing and hashing
 * the library. With [failFirst], the first listing fails instead, and says so on [failed].
 */
private class HeldListing(
    failFirst: Boolean = false,
) : FsGate() {
    val failed = CompletableDeferred<Unit>()
    private val failure = AtomicBoolean(failFirst)

    fun over(fs: LibraryFileSystem): LibraryFileSystem =
        object : LibraryFileSystem by fs {
            override suspend fun listAllIndexable(): List<FileMeta> {
                if (failure.getAndSet(false)) {
                    failed.complete(Unit)
                    throw IOException("Initial rescan listing failed")
                }
                pause()
                return fs.listAllIndexable()
            }
        }
}
