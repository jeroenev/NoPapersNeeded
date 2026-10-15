package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

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

    /** Insert [job] unless one with its id exists; the new row id, or -1 when it was kept out. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertJobIfAbsent(job: AiEditJobEntity): Long

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertDocsIfAbsent(rows: List<AiEditJobDocEntity>)

    /** Persist a submitted run and all its documents together, so interruption leaves no orphan. */
    @Transaction
    suspend fun insertRun(
        job: AiEditJobEntity,
        docs: List<AiEditJobDocEntity>,
    ) {
        upsertJob(job)
        upsertDocs(docs)
    }

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
