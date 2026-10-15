package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entries: List<AiEnrichQueueEntity>)

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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun markEnrichedIfAbsent(entries: List<AiEnrichedEntity>)
}
