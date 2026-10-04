package dev.npn.core.app

import androidx.room3.useReaderConnection
import dev.npn.core.db.AiEditJobDocEntity
import dev.npn.core.db.AiEditJobEntity
import dev.npn.core.db.LegacyIndexTables
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.createFromSchema
import dev.npn.core.db.exec
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.NpnJson
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.collections.shouldNotContainAnyOf
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Opening a library over the index an older build left behind: what the index held that it cannot
 * rebuild reaches its new home before the index forgets it.
 */
class IndexUpgradeTest {
    private lateinit var library: Path
    private lateinit var data: File

    @BeforeTest
    fun setup() {
        library = Files.createTempDirectory("npn-upgrade-lib")
        data = Files.createTempDirectory("npn-upgrade-data").toFile()
    }

    @AfterTest
    fun teardown() {
        library.toFile().deleteRecursively()
        data.deleteRecursively()
    }

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
     * What an index from before device.db held for this device — the device id and the v1 writer's
     * state, queued edit runs, the enrichment queue and its marks, the OCR queue with a paid-for
     * request and pages, chats — reaches the device database, and the index keeps none of it.
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
                device.keyValueDao().getAll(LEGACY_KV.keys.toList()) shouldBe LEGACY_KV - OBSERVATION_KEY
                device.aiEditJobDao().docsFor("job-1").single().suggestionJson shouldBe "{\"paid\":true}"
                session.work.editRuns
                    .first()
                    .single()
                    .jobId shouldBe "job-1"
                device.aiEnrichQueueDao().getByDocId("b".repeat(64))?.attempts shouldBe 1
                device.aiEnrichQueueDao().enrichedAt(doc) shouldBe 42
                device.ocrQueueDao().getByDocId(doc)?.aiRequested shouldBe true
                device.ocrQueueDao().cachedPages(doc, "ai", "eng").single().text shouldBe "paid page"

                // The index keeps only what it can rebuild: its own observations, not the moved state.
                deps.db.keyValueDao().getAll(LEGACY_KV.keys.toList()) shouldBe mapOf(OBSERVATION_KEY to "seen")
                deps.db.tableNames() shouldNotContainAnyOf MOVED_TABLES
                deps.db.columnNames("documents") shouldNotContain "aiEnrichedAt"
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
                val queued = deps.deviceDb.ocrQueueDao().getByDocId(docId).shouldNotBeNull()
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
                val owed = deps.deviceDb.ocrQueueDao().getByDocId(docId).shouldNotBeNull()
                owed.attempts shouldBe 2
                owed.aiRequested shouldBe true
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
