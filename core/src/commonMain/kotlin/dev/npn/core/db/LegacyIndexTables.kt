package dev.npn.core.db

import androidx.room3.PooledConnection
import androidx.room3.executeSQL
import androidx.room3.immediateTransaction
import androidx.room3.useReaderConnection
import androidx.room3.useWriterConnection
import androidx.room3.withWriteTransaction
import androidx.sqlite.SQLiteStatement

/**
 * Tables older index schemas held that the current one does not: data the index used to keep but
 * cannot rebuild. A migration leaves them in place (see [NpnDatabase.MIGRATIONS]), since it sees
 * only this database; the session moves their rows to where that data lives now, then drops them.
 * Read as plain SQL, because they are no longer part of the schema Room knows.
 */
internal object LegacyIndexTables {
    const val SAVED_VIEWS = "saved_views"

    /** Where the v24 migration keeps `documents.aiEnrichedAt` until it reaches [DeviceDatabase]. */
    const val AI_ENRICHED = "ai_enriched"

    private const val EDIT_JOBS = "ai_edit_jobs"
    private const val EDIT_JOB_DOCS = "ai_edit_job_docs"
    private const val ENRICH_QUEUE = "ai_enrich_queue"
    private const val OCR_QUEUE = "ocr_queue"
    private const val OCR_PAGES = "ocr_page_cache"
    private val DEVICE_TABLES = listOf(EDIT_JOB_DOCS, EDIT_JOBS, ENRICH_QUEUE, AI_ENRICHED, OCR_PAGES, OCR_QUEUE)

    /** The serialized views the index still holds, or null once their table is gone. */
    suspend fun savedViews(db: NpnDatabase): List<String>? =
        db.useReaderConnection { connection ->
            connection.rows(SAVED_VIEWS) { it.text("json") }
        }

    /** Drop [table], whose rows now live elsewhere. */
    suspend fun drop(
        db: NpnDatabase,
        table: String,
    ) = db.useWriterConnection { it.executeSQL("DROP TABLE IF EXISTS $table") }

    /**
     * Move this device's state out of [index] into [device]: the tables the v24 migration left
     * behind, and the `kv` rows under [keys] or starting with [keyPrefix]. The copy commits before
     * anything is removed from the index, so a crash in between only means copying the same rows
     * again on the next open. A few lookups once the index holds none of it.
     */
    suspend fun moveDeviceState(
        index: NpnDatabase,
        device: DeviceDatabase,
        keys: Collection<String>,
        keyPrefix: String,
    ) {
        val state = index.useReaderConnection { connection -> connection.deviceState(keys, keyPrefix) }
        if (state.isEmpty) return
        device.withWriteTransaction {
            device.keyValueDao().putAll(state.kv)
            state.editJobs.forEach { device.aiEditJobDao().upsertJob(it) }
            device.aiEditJobDao().upsertDocs(state.editJobDocs)
            state.enrichQueue.forEach { device.aiEnrichQueueDao().upsert(it) }
            state.enriched.forEach { device.aiEnrichQueueDao().markEnriched(it) }
            state.ocrQueue.forEach { device.ocrQueueDao().upsert(it) }
            state.ocrPages.forEach { device.ocrQueueDao().cachePage(it) }
        }
        index.useWriterConnection { connection ->
            connection.immediateTransaction {
                state.tables.forEach { executeSQL("DROP TABLE IF EXISTS $it") }
                state.kv.forEach { row ->
                    usePrepared("DELETE FROM kv WHERE key = ?") { statement ->
                        statement.bindText(1, row.key)
                        statement.step()
                    }
                }
            }
        }
    }

    private suspend fun PooledConnection.deviceState(
        keys: Collection<String>,
        keyPrefix: String,
    ): DeviceState =
        DeviceState(
            kv = keyValues(keys, keyPrefix),
            editJobs =
                rows(EDIT_JOBS) {
                    AiEditJobEntity(
                        jobId = it.text("jobId"),
                        requestJson = it.text("requestJson"),
                        createdAt = it.long("createdAt"),
                        updatedAt = it.long("updatedAt"),
                        attempts = it.long("attempts").toInt(),
                        nextAttemptAt = it.long("nextAttemptAt"),
                        lastError = it.textOrNull("lastError"),
                    )
                }.orEmpty(),
            editJobDocs =
                rows(EDIT_JOB_DOCS) {
                    AiEditJobDocEntity(
                        jobId = it.text("jobId"),
                        docId = it.text("docId"),
                        position = it.long("position").toInt(),
                        suggestionJson = it.textOrNull("suggestionJson"),
                        attempts = it.long("attempts").toInt(),
                        failed = it.long("failed") != 0L,
                        lastError = it.textOrNull("lastError"),
                    )
                }.orEmpty(),
            enrichQueue =
                rows(ENRICH_QUEUE) {
                    AiEnrichQueueEntity(
                        docId = it.text("docId"),
                        attempts = it.long("attempts").toInt(),
                        nextAttemptAt = it.long("nextAttemptAt"),
                        updatedAt = it.long("updatedAt"),
                        lastError = it.textOrNull("lastError"),
                    )
                }.orEmpty(),
            enriched = rows(AI_ENRICHED) { AiEnrichedEntity(it.text("docId"), it.long("enrichedAt")) }.orEmpty(),
            ocrQueue =
                rows(OCR_QUEUE) {
                    OcrQueueEntity(
                        docId = it.text("docId"),
                        state = it.text("state"),
                        attempts = it.long("attempts").toInt(),
                        updatedAt = it.long("updatedAt"),
                        lastError = it.textOrNull("lastError"),
                        rerun = it.long("rerun") != 0L,
                        aiRequested = it.long("aiRequested") != 0L,
                        nextAttemptAt = it.long("nextAttemptAt"),
                    )
                }.orEmpty(),
            ocrPages =
                rows(OCR_PAGES) {
                    OcrPageCacheEntity(
                        docId = it.text("docId"),
                        engine = it.text("engine"),
                        lang = it.text("lang"),
                        page = it.long("page").toInt(),
                        text = it.text("text"),
                    )
                }.orEmpty(),
            tables = DEVICE_TABLES.filter { hasTable(it) },
        )

    /** The `kv` rows under [keys] or [prefix]; the prefix as a key range, so the primary key serves it. */
    private suspend fun PooledConnection.keyValues(
        keys: Collection<String>,
        prefix: String,
    ): List<KeyValueEntity> {
        val sql = "SELECT key, value FROM kv WHERE key IN (${keys.joinToString { "?" }}) OR (key >= ? AND key < ?)"
        return usePrepared(sql) { statement ->
            keys.forEachIndexed { i, key -> statement.bindText(i + 1, key) }
            statement.bindText(keys.size + 1, prefix)
            statement.bindText(keys.size + 2, prefix.dropLast(1) + (prefix.last() + 1))
            buildList { while (statement.step()) add(KeyValueEntity(statement.getText(0), statement.getText(1))) }
        }
    }

    /** Every row of [table] through [read], or null when the index has no such table. */
    private suspend fun <T> PooledConnection.rows(
        table: String,
        read: (Row) -> T,
    ): List<T>? {
        if (!hasTable(table)) return null
        return usePrepared("SELECT * FROM $table") { statement ->
            val row = Row(statement)
            buildList { while (statement.step()) add(read(row)) }
        }
    }

    private suspend fun PooledConnection.hasTable(name: String): Boolean =
        usePrepared("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?") { statement ->
            statement.bindText(1, name)
            statement.step()
        }

    /** The current row of [statement], its columns read by name. */
    private class Row(
        private val statement: SQLiteStatement,
    ) {
        private val columns = statement.getColumnNames().withIndex().associate { (i, name) -> name to i }

        fun text(name: String): String = statement.getText(columns.getValue(name))

        fun textOrNull(name: String): String? =
            columns.getValue(name).takeUnless(statement::isNull)?.let(statement::getText)

        fun long(name: String): Long = statement.getLong(columns.getValue(name))
    }

    private class DeviceState(
        val kv: List<KeyValueEntity>,
        val editJobs: List<AiEditJobEntity>,
        val editJobDocs: List<AiEditJobDocEntity>,
        val enrichQueue: List<AiEnrichQueueEntity>,
        val enriched: List<AiEnrichedEntity>,
        val ocrQueue: List<OcrQueueEntity>,
        val ocrPages: List<OcrPageCacheEntity>,
        /** The legacy tables the index still has, dropped once their rows are safe. */
        val tables: List<String>,
    ) {
        val isEmpty: Boolean get() = kv.isEmpty() && tables.isEmpty()
    }
}
