package dev.npn.core.ai

import dev.npn.core.db.AiUsageDao
import dev.npn.core.db.AiUsageEntity

/** [AiLedger] over the device database's `ai_usage` table. */
internal class RoomAiLedger(
    private val dao: AiUsageDao,
) : AiLedger {
    override suspend fun record(record: AiUsageRecord) {
        dao.insert(record.toEntity())
    }

    override suspend fun totals(sinceMillis: Long?): List<AiUsageTotals> =
        (if (sinceMillis == null) dao.totals() else dao.totalsSince(sinceMillis)).map { it.toTotals() }

    override suspend fun reset() = dao.deleteAll()

    private fun AiUsageRecord.toEntity(): AiUsageEntity =
        AiUsageEntity(
            provider = provider,
            model = model,
            kind = kind.name,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            estimatedCostUsd = estimatedCostUsd,
            estimated = estimated,
            recordedAt = recordedAt,
            runId = runId,
        )
}

private fun dev.npn.core.db.AiUsageAggregate.toTotals(): AiUsageTotals =
    AiUsageTotals(
        provider = provider,
        model = model,
        kind = AiUsageKind.entries.firstOrNull { it.name == kind },
        requests = requests,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        estimatedCostUsd = estimatedCostUsd,
        unpricedRequests = unpricedRequests,
        estimatedRequests = estimatedRequests,
    )
