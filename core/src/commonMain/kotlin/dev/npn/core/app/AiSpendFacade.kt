package dev.npn.core.app

import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiEditRequest
import dev.npn.core.ai.AiEstimateDoc
import dev.npn.core.ai.AiEstimator
import dev.npn.core.ai.AiLedger
import dev.npn.core.ai.AiPrices
import dev.npn.core.ai.AiPrompts
import dev.npn.core.ai.AiRunEstimate
import dev.npn.core.ai.AiSpendSummary
import dev.npn.core.db.AiUsageDao
import dev.npn.core.db.FtsDao
import dev.npn.core.util.EpochClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The rolling window the settings totals show first. */
const val AI_SPEND_WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

/**
 * The AI spend view of one library's session: the totals of the spend ledger this device keeps for
 * the library, and a pre-flight estimate for a bulk run. Recording is not here — the AI facade
 * records each request as it happens — so a caller showing a confirmation or the settings pane
 * only reads.
 */
interface AiSpendFacade {
    /** Ledger totals, refreshed whenever a request is recorded. */
    val summary: Flow<AiSpendSummary>

    /** Delete every request this device recorded for this library. */
    suspend fun reset()

    /** What a suggestion run over [docIds] with [request] would cost, before any call is made. */
    suspend fun estimateSuggest(
        docIds: List<String>,
        request: AiEditRequest,
    ): AiRunEstimate

    /** What reading one document's [pages] would cost with the vision model. */
    suspend fun estimateOcrForPages(pages: Int): AiRunEstimate
}

internal class AiSpendFacadeImpl(
    private val fts: FtsDao,
    private val dao: AiUsageDao,
    private val ledger: AiLedger,
    private val prices: AiPrices,
    private val configProvider: suspend () -> AiConfig,
    private val clock: EpochClock,
) : AiSpendFacade {
    override val summary: Flow<AiSpendSummary>
        get() =
            dao.observeCount().map {
                AiSpendSummary(
                    last30Days = ledger.totals(clock.nowMillis() - AI_SPEND_WINDOW_MS),
                    allTime = ledger.totals(null),
                )
            }

    override suspend fun reset() = ledger.reset()

    override suspend fun estimateSuggest(
        docIds: List<String>,
        request: AiEditRequest,
    ): AiRunEstimate {
        val config = configProvider()
        val textChars =
            docIds.associateWith { docId ->
                minOf(fts.textOf(docId)?.length ?: 0, AiPrompts.MAX_DOC_TEXT_CHARS)
            }
        val tokens =
            AiEstimator.suggest(
                documents = docIds.map { AiEstimateDoc(textChars = textChars.getValue(it)) },
                fields = request.fields.size,
                tags = request.tags,
                tagRemovals = request.tagRemovals,
                customPrompt = !request.customPrompt.isNullOrBlank(),
            )
        return estimate(config, docIds.size, docIds.size, pages = 0, tokens.inputTokens, tokens.outputTokens)
    }

    override suspend fun estimateOcrForPages(pages: Int): AiRunEstimate {
        val tokens = AiEstimator.ocr(pages)
        return estimate(
            configProvider(),
            documents = 1,
            requests = pages,
            pages = pages,
            tokens.inputTokens,
            tokens.outputTokens,
        )
    }

    private suspend fun estimate(
        config: AiConfig,
        documents: Int,
        requests: Int,
        pages: Int,
        inputTokens: Long,
        outputTokens: Long,
    ): AiRunEstimate {
        val price = prices.priceOf(config)
        return AiRunEstimate(
            documents = documents,
            requests = requests,
            pages = pages,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            estimatedCostUsd = price?.cost(inputTokens, outputTokens),
            priceKnown = price != null,
            requestCap = config.maxRunRequests,
        )
    }
}
