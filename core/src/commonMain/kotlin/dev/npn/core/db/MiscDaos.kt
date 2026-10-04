package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DocumentFieldDao {
    /** All custom fields for a document, ordered by key. Observed for live UI updates. */
    @Query("SELECT * FROM document_fields WHERE docId = :docId ORDER BY fieldKey COLLATE NOCASE")
    fun observeForDoc(docId: String): Flow<List<DocumentFieldEntity>>

    /** Custom fields for a set of documents, for attaching to list items in one pass. */
    @Query("SELECT * FROM document_fields WHERE docId IN (:docIds)")
    suspend fun fieldsForDocsChunk(docIds: List<String>): List<DocumentFieldEntity>

    /** Chunked [fieldsForDocsChunk] so large id sets stay under SQLite's bind-variable limit. */
    suspend fun fieldsForDocs(docIds: List<String>): List<DocumentFieldEntity> =
        docIds.chunkedBind { fieldsForDocsChunk(it) }

    /** All custom-field rows, for the AI's field search tools. */
    @Query("SELECT * FROM document_fields")
    suspend fun all(): List<DocumentFieldEntity>

    /** Distinct values of one field on live documents, for autocomplete and AI vocabulary. */
    @Query(DISTINCT_VALUES)
    suspend fun distinctValues(
        key: String,
        limit: Int,
    ): List<String>

    /** [distinctValues], observed. */
    @Query(DISTINCT_VALUES)
    fun observeDistinctValues(
        key: String,
        limit: Int,
    ): Flow<List<String>>

    /** Distinct values of one field on live documents starting with [likePrefix] (LIKE-escaped, `\`). */
    @Query(
        "SELECT DISTINCT f.fieldValue FROM document_fields f JOIN documents d ON d.docId = f.docId " +
            "WHERE f.fieldKey = :key AND f.fieldValue LIKE :likePrefix ESCAPE '\\' " +
            "AND $LIVE_DOCUMENT " +
            "ORDER BY f.fieldValue COLLATE NOCASE LIMIT :limit",
    )
    suspend fun valuesForPrefix(
        key: String,
        likePrefix: String,
        limit: Int,
    ): List<String>

    /** Document ids carrying a value for [key]. */
    @Query("SELECT docId FROM document_fields WHERE fieldKey = :key")
    suspend fun docIdsWithKey(key: String): List<String>

    /** Distinct field keys with counts limited to documents the AI search tools can reach. */
    @Query(
        "SELECT f.fieldKey, COUNT(*) AS count FROM document_fields f " +
            "JOIN documents d ON d.docId = f.docId " +
            "WHERE $LIVE_DOCUMENT " +
            "GROUP BY f.fieldKey ORDER BY f.fieldKey COLLATE NOCASE",
    )
    suspend fun keysWithLiveCounts(): List<FieldKeyCount>

    @Query(
        "SELECT fieldKey, COUNT(*) AS count FROM document_fields " +
            "GROUP BY fieldKey ORDER BY fieldKey COLLATE NOCASE",
    )
    fun observeKeysWithCounts(): Flow<List<FieldKeyCount>>

    @Upsert
    suspend fun upsertAll(fields: List<DocumentFieldEntity>)

    /** Replace the field rows of several documents atomically, keyed by docId. */
    @Transaction
    suspend fun replaceForDocs(updates: Map<String, List<DocumentFieldEntity>>) {
        if (updates.isEmpty()) return
        for (chunk in updates.keys.chunked(BIND_CHUNK)) clearFieldsFor(chunk)
        upsertAll(updates.values.flatten())
    }

    @Query("DELETE FROM document_fields WHERE docId IN (:docIds)")
    suspend fun clearFieldsFor(docIds: List<String>)
}

@Dao
interface ThumbnailDao {
    @Query("SELECT * FROM thumbnails WHERE docId = :docId")
    suspend fun get(docId: String): ThumbnailEntity?

    @Query("SELECT * FROM thumbnails")
    suspend fun getAll(): List<ThumbnailEntity>

    @Upsert
    suspend fun upsert(thumbnail: ThumbnailEntity)

    @Query("DELETE FROM thumbnails")
    suspend fun deleteAll()
}

@Dao
interface DuplicateDao {
    @Query("SELECT * FROM duplicates ORDER BY docId, relPath")
    fun observeAll(): Flow<List<DuplicateEntity>>

    @Query("SELECT * FROM duplicates WHERE docId = :docId")
    suspend fun forDoc(docId: String): List<DuplicateEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<DuplicateEntity>)

    @Query("DELETE FROM duplicates WHERE docId = :docId")
    suspend fun deleteForDoc(docId: String)

    /** Replace all duplicate paths recorded for a document. */
    @Transaction
    suspend fun replaceAll(
        docId: String,
        rows: List<DuplicateEntity>,
    ) {
        deleteForDoc(docId)
        if (rows.isNotEmpty()) insertAll(rows)
    }

    @Query("DELETE FROM duplicates WHERE docId IN (:docIds)")
    suspend fun deleteForDocs(docIds: List<String>)

    /** Replace the duplicate paths recorded for each of [docIds] with those in [rows], at once. */
    @Transaction
    suspend fun replaceForDocs(
        docIds: Collection<String>,
        rows: List<DuplicateEntity>,
    ) {
        for (chunk in docIds.chunked(BIND_CHUNK)) deleteForDocs(chunk)
        if (rows.isNotEmpty()) insertAll(rows)
    }
}

@Dao
interface OcrQueueDao {
    /**
     * Oldest row still owed a pass and due at [now], i.e. not waiting out a retry backoff.
     *
     * "Owed" is written as *not terminal* rather than `state = 'pending'` exactly, so the queue has
     * one notion of unfinished work and any row that is neither done nor given up on gets drained.
     * That also reclaims rows left behind by builds that wrote a `running` state: nothing clears
     * one once its process is gone, and matching on `pending` alone would skip them forever (see
     * [OcrQueueEntity]).
     */
    @Query(
        "SELECT * FROM ocr_queue WHERE state NOT IN ('$QUEUE_DONE', '$QUEUE_FAILED') AND nextAttemptAt <= :now " +
            "ORDER BY updatedAt LIMIT 1",
    )
    suspend fun nextPending(now: Long): OcrQueueEntity?

    @Upsert
    suspend fun upsert(entry: OcrQueueEntity)

    @Query("SELECT * FROM ocr_queue ORDER BY updatedAt")
    fun observeQueue(): Flow<List<OcrQueueEntity>>

    /**
     * Rows still owed something: waiting, being worked on, or given up on after the attempt cap.
     * Excludes `done`, which this table keeps as history rather than deleting, for
     * [dev.npn.core.app.WorkFacade.queue].
     */
    @Query("SELECT * FROM ocr_queue WHERE state <> '$QUEUE_DONE' ORDER BY updatedAt")
    fun observeOutstanding(): Flow<List<OcrQueueEntity>>

    @Query("SELECT * FROM ocr_queue WHERE docId = :docId")
    suspend fun getByDocId(docId: String): OcrQueueEntity?

    /**
     * Count of rows still owed a pass, for [dev.npn.core.app.OcrStatus]. Counts exactly what
     * [nextPending] would eventually take, including rows waiting out a backoff, so the number the
     * user sees drains to zero; rows given up on are reported separately, as failures rather than
     * as work outstanding.
     */
    @Query("SELECT COUNT(*) FROM ocr_queue WHERE state NOT IN ('$QUEUE_DONE', '$QUEUE_FAILED')")
    fun countActive(): Flow<Int>

    @Query("DELETE FROM ocr_queue WHERE docId = :docId")
    suspend fun deleteRow(docId: String)

    /** Drop [docId] from the queue, with any pages its runs read: nothing will resume them now. */
    @Transaction
    suspend fun delete(docId: String) {
        deleteRow(docId)
        clearCachedPages(docId)
    }

    /** Pages a failed run of [engine] in [lang] already read for [docId] (see [OcrPageCacheEntity]). */
    @Query("SELECT * FROM ocr_page_cache WHERE docId = :docId AND engine = :engine AND lang = :lang")
    suspend fun cachedPages(
        docId: String,
        engine: String,
        lang: String,
    ): List<OcrPageCacheEntity>

    @Upsert
    suspend fun cachePage(page: OcrPageCacheEntity)

    @Query("DELETE FROM ocr_page_cache WHERE docId = :docId")
    suspend fun clearCachedPages(docId: String)
}

@Dao
interface AiEnrichQueueDao {
    /**
     * Oldest due row, never-tried ones first.
     *
     * Ordering by [AiEnrichQueueEntity.attempts] before [AiEnrichQueueEntity.nextAttemptAt] is what
     * keeps one failing document from costing fresh imports their enrichment: a failure stops the
     * whole drain, so a retry that sorted ahead of a just-imported document would spend the kick on
     * itself and leave the new document waiting for the next one.
     */
    @Query("SELECT * FROM ai_enrich_queue WHERE nextAttemptAt <= :now ORDER BY attempts, nextAttemptAt LIMIT 1")
    suspend fun nextDue(now: Long): AiEnrichQueueEntity?

    @Upsert
    suspend fun upsert(entry: AiEnrichQueueEntity)

    @Query("SELECT * FROM ai_enrich_queue WHERE docId = :docId")
    suspend fun getByDocId(docId: String): AiEnrichQueueEntity?

    /**
     * Every row owed a pass, in the order [nextDue] will take them, for
     * [dev.npn.core.app.WorkFacade.queue]. Existence is the whole state here, so unlike the OCR
     * queue there is nothing to filter out.
     */
    @Query("SELECT * FROM ai_enrich_queue ORDER BY attempts, nextAttemptAt")
    fun observeQueue(): Flow<List<AiEnrichQueueEntity>>

    @Query("DELETE FROM ai_enrich_queue WHERE docId = :docId")
    suspend fun delete(docId: String)

    /** When this device enriched [docId], or null when it has not ([AiEnrichedEntity]). */
    @Query("SELECT enrichedAt FROM ai_enriched WHERE docId = :docId")
    suspend fun enrichedAt(docId: String): Long?

    @Upsert
    suspend fun markEnriched(entry: AiEnrichedEntity)
}

/**
 * How much of a queued AI edit has been suggested, aggregated per job so the queue view never
 * pulls whole suggestion payloads just to draw a progress bar.
 */
data class AiEditJobProgress(
    val jobId: String,
    val total: Int,
    /** Documents that will not be asked again: suggested or given up on. */
    val done: Int,
    val failures: Int,
)

@Dao
interface AiEditJobDao {
    /**
     * Oldest due job that still has a document owed a call, never-tried ones first — the same
     * "retries yield to fresh work" ordering as [AiEnrichQueueDao.nextDue], for the same reason:
     * a failure parks the whole drain, so a backed-off job must not spend the kick a newly queued
     * one deserves.
     *
     * A job with nothing left owed is deliberately not returned: it is finished and waiting for a
     * human, not for the drain.
     */
    @Query(
        """
        SELECT * FROM ai_edit_jobs AS j
        WHERE j.nextAttemptAt <= :now
          AND EXISTS (
            SELECT 1 FROM ai_edit_job_docs AS d
            WHERE d.jobId = j.jobId AND d.suggestionJson IS NULL AND d.failed = 0
          )
        ORDER BY j.attempts, j.createdAt
        LIMIT 1
        """,
    )
    suspend fun nextDueJob(now: Long): AiEditJobEntity?

    @Query("SELECT * FROM ai_edit_jobs WHERE jobId = :jobId")
    suspend fun job(jobId: String): AiEditJobEntity?

    @Query("SELECT * FROM ai_edit_jobs ORDER BY createdAt")
    fun observeJobs(): Flow<List<AiEditJobEntity>>

    @Upsert
    suspend fun upsertJob(job: AiEditJobEntity)

    /** The next documents of [jobId] still owed a model call, in submitted order. */
    @Query(
        """
        SELECT * FROM ai_edit_job_docs
        WHERE jobId = :jobId AND suggestionJson IS NULL AND failed = 0
        ORDER BY position
        LIMIT :limit
        """,
    )
    suspend fun nextPendingDocs(
        jobId: String,
        limit: Int,
    ): List<AiEditJobDocEntity>

    /** Every document of [jobId], in submitted order, for building the Review. */
    @Query("SELECT * FROM ai_edit_job_docs WHERE jobId = :jobId ORDER BY position")
    suspend fun docsFor(jobId: String): List<AiEditJobDocEntity>

    @Upsert
    suspend fun upsertDocs(rows: List<AiEditJobDocEntity>)

    /**
     * Write what a drain step learned about [job]: its own row and [docs], in one transaction and
     * only while the job still exists. A run discarded while its model calls were in flight stays
     * discarded. Returns whether anything was written.
     */
    @Transaction
    suspend fun writeBackIfPresent(
        job: AiEditJobEntity,
        docs: List<AiEditJobDocEntity>,
    ): Boolean {
        if (job(job.jobId) == null) return false
        if (docs.isNotEmpty()) upsertDocs(docs)
        upsertJob(job)
        return true
    }

    /** Drop documents that stopped being worth a call — trashed or deleted since they were queued. */
    @Query("DELETE FROM ai_edit_job_docs WHERE jobId = :jobId AND docId IN (:docIds)")
    suspend fun deleteDocs(
        jobId: String,
        docIds: List<String>,
    )

    /** Per-job progress for [dev.npn.core.app.WorkFacade.editRuns]. */
    @Query(
        """
        SELECT jobId,
               COUNT(*) AS total,
               SUM(CASE WHEN suggestionJson IS NOT NULL OR failed <> 0 THEN 1 ELSE 0 END) AS done,
               SUM(CASE WHEN failed <> 0 THEN 1 ELSE 0 END) AS failures
        FROM ai_edit_job_docs
        GROUP BY jobId
        """,
    )
    fun observeProgress(): Flow<List<AiEditJobProgress>>

    /**
     * Drop a job and everything it holds. One transaction because a job row without its documents
     * would be a run the drain can never finish and the user can never open.
     */
    @Transaction
    suspend fun deleteJob(jobId: String) {
        deleteJobDocs(jobId)
        deleteJobRow(jobId)
    }

    @Query("DELETE FROM ai_edit_job_docs WHERE jobId = :jobId")
    suspend fun deleteJobDocs(jobId: String)

    @Query("DELETE FROM ai_edit_jobs WHERE jobId = :jobId")
    suspend fun deleteJobRow(jobId: String)
}

@Dao
interface KeyValueDao {
    @Query("SELECT value FROM kv WHERE key = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun put(entry: KeyValueEntity)

    suspend fun put(
        key: String,
        value: String,
    ) = put(KeyValueEntity(key, value))

    @Upsert
    suspend fun putAll(entries: List<KeyValueEntity>)

    @Query("SELECT * FROM kv WHERE key IN (:keys)")
    suspend fun getChunk(keys: List<String>): List<KeyValueEntity>

    /** The stored value of each of [keys] that has one, read in bind-limit slices. */
    suspend fun getAll(keys: List<String>): Map<String, String> =
        keys.chunkedBind { getChunk(it) }.associate { it.key to it.value }

    @Query("DELETE FROM kv WHERE key = :key")
    suspend fun delete(key: String)
}

private const val DISTINCT_VALUES =
    "SELECT DISTINCT f.fieldValue FROM document_fields f JOIN documents d ON d.docId = f.docId " +
        "WHERE f.fieldKey = :key AND $LIVE_DOCUMENT " +
        "ORDER BY f.fieldValue COLLATE NOCASE LIMIT :limit"

/** Custom-field key usage count, for the AI's list_fields tool. */
data class FieldKeyCount(
    val fieldKey: String,
    val count: Int,
)
