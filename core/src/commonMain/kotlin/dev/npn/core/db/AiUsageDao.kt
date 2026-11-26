package dev.npn.core.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** One provider, model and kind line in the spend ledger's aggregation. */
data class AiUsageAggregate(
    val provider: String,
    val model: String,
    val kind: String,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: Double?,
    val unpricedRequests: Int,
    val estimatedRequests: Int,
)

/** This library's AI spend ledger on this device: what was spent, never what was said. */
@Dao
interface AiUsageDao {
    @Insert
    suspend fun insert(row: AiUsageEntity)

    @Query(
        "SELECT provider, model, kind, COUNT(*) AS requests, " +
            "COALESCE(SUM(inputTokens), 0) AS inputTokens, " +
            "COALESCE(SUM(outputTokens), 0) AS outputTokens, " +
            "SUM(estimatedCostUsd) AS estimatedCostUsd, " +
            "SUM(CASE WHEN estimatedCostUsd IS NULL THEN 1 ELSE 0 END) AS unpricedRequests, " +
            "SUM(CASE WHEN estimated THEN 1 ELSE 0 END) AS estimatedRequests " +
            "FROM ai_usage WHERE recordedAt >= :since GROUP BY provider, model, kind ORDER BY requests DESC",
    )
    suspend fun totalsSince(since: Long): List<AiUsageAggregate>

    @Query(
        "SELECT provider, model, kind, COUNT(*) AS requests, " +
            "COALESCE(SUM(inputTokens), 0) AS inputTokens, " +
            "COALESCE(SUM(outputTokens), 0) AS outputTokens, " +
            "SUM(estimatedCostUsd) AS estimatedCostUsd, " +
            "SUM(CASE WHEN estimatedCostUsd IS NULL THEN 1 ELSE 0 END) AS unpricedRequests, " +
            "SUM(CASE WHEN estimated THEN 1 ELSE 0 END) AS estimatedRequests " +
            "FROM ai_usage GROUP BY provider, model, kind ORDER BY requests DESC",
    )
    suspend fun totals(): List<AiUsageAggregate>

    @Query("DELETE FROM ai_usage")
    suspend fun deleteAll()

    /** Emits on every ledger write, so the settings totals follow a run while it is open. */
    @Query("SELECT COUNT(*) FROM ai_usage")
    fun observeCount(): Flow<Int>
}
