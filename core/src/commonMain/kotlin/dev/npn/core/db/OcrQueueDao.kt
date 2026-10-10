package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Query
import androidx.room3.Transaction
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

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

    /**
     * Write what a pass over [docId] came to: [settled], built from the row as it is now. A row that
     * holds a request ([OcrQueueEntity.rerun], [OcrQueueEntity.aiRequested]) made while the pass ran
     * is left as it is, owed, for the drain to run that request. A request is new when [seen], the
     * row the pass started from, lacked it, or when it was asked again, which stamps the row afresh
     * ([OcrQueueEntity.updatedAt]). Returns the row written, or null when it was left for a request.
     */
    @Transaction
    suspend fun settle(
        docId: String,
        seen: OcrQueueEntity?,
        settled: (OcrQueueEntity?) -> OcrQueueEntity,
    ): OcrQueueEntity? {
        val row = getByDocId(docId)
        val rerunAsked = row?.rerun == true && seen?.rerun != true
        val aiAsked = row?.aiRequested == true && seen?.aiRequested != true
        val askedAgain = row != null && (row.rerun || row.aiRequested) && row.updatedAt != seen?.updatedAt
        if (rerunAsked || aiAsked || askedAgain) return null
        val written = settled(row)
        upsert(written)
        return written
    }

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
