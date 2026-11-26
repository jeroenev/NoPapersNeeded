package dev.npn.core.db

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * `device.db` is never dropped (see [DeviceDatabase]), so a schema a build shipped must never
 * change and every later version must migrate from it. This pins what shipped, and opens every
 * shipped schema through the production builder, so a changed schema without a version bump, or a
 * new version without a migration, fails here rather than on a user's device.
 */
class DeviceDatabaseSchemaTest {
    private val dir: File = Files.createTempDirectory("npn-device-schema").toFile()

    @AfterTest
    fun teardown() {
        dir.deleteRecursively()
    }

    @Test
    fun everyExportedSchemaIsTheOneThatShipped() {
        val exported = schemaFiles()
        exported.keys shouldBe SHIPPED.keys
        exported.forEach { (version, file) -> identityHash(file) shouldBe SHIPPED.getValue(version) }
    }

    @Test
    fun everyShippedVersionOpensThroughTheProductionBuilder() =
        runTest {
            for (version in SHIPPED.keys) {
                val file = File(dir, "device-$version.db")
                createFromSchema(file, version, database = DATABASE) {}
                val db = deviceDatabase(file)
                try {
                    db.ocrQueueDao().nextPending(now = 0) shouldBe null
                } finally {
                    db.close()
                }
            }
        }

    /**
     * A v2 database with an unfinished queued run, as a build before the spend ledger left it: the
     * migration keeps the run and its documents, starts it with no requests spent (v2 counted none),
     * and creates the ledger empty.
     */
    @Test
    fun aVersionTwoDatabaseWithAQueuedRunMigratesToVersionThree() =
        runTest {
            val file = File(dir, "device-2-to-3.db")
            createFromSchema(file, version = 2, database = DATABASE) { connection ->
                connection.exec(
                    "INSERT INTO ai_edit_jobs (jobId, requestJson, createdAt, updatedAt, attempts, nextAttemptAt, " +
                        "lastError) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    "job-1",
                    "{}",
                    1_000,
                    1_000,
                    2,
                    5_000,
                    "endpoint down",
                )
                connection.exec(
                    "INSERT INTO ai_edit_job_docs (jobId, docId, position, suggestionJson, attempts, failed, " +
                        "lastError) VALUES (?, ?, ?, ?, ?, ?, ?)",
                    "job-1",
                    "doc-a",
                    0,
                    null,
                    1,
                    false,
                    null,
                )
            }
            val db = deviceDatabase(file)
            try {
                val job = db.aiEditJobDao().job("job-1")
                job?.requestsUsed shouldBe 0
                job?.attempts shouldBe 2
                job?.lastError shouldBe "endpoint down"
                db.aiEditJobDao().docsFor("job-1").map { it.docId } shouldBe listOf("doc-a")
                db.aiUsageDao().totals() shouldBe emptyList()
            } finally {
                db.close()
            }
        }

    private fun schemaFiles(): Map<Int, File> =
        File("schemas/$DATABASE")
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .associateBy { it.nameWithoutExtension.toInt() }

    private fun identityHash(file: File): String =
        Json
            .parseToJsonElement(file.readText())
            .jsonObject
            .getValue("database")
            .jsonObject
            .getValue("identityHash")
            .jsonPrimitive.content

    private companion object {
        const val DATABASE = "dev.npn.core.db.DeviceDatabase"

        /**
         * Every `device.db` version a build shipped, with the identity hash Room exported for it.
         * Append only: never edit or remove an entry. A schema change raises the version, adds a
         * real migration and adds its line here.
         */
        val SHIPPED: Map<Int, String> =
            mapOf(
                1 to "c682552d5e6662738f8589c47bbd5f32",
                2 to "2de9d041f774d090fff7fd96af3375f6",
                3 to "c77405c86e76ba0a623e2093be92eef8",
            )
    }
}
