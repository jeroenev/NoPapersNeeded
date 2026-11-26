package dev.npn.core.ai

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * What an AI request was for. Stored verbatim in the spend ledger (`device.db`), so the spelling of
 * a constant is a storage format; add new entries at the end.
 */
enum class AiUsageKind {
    CHAT,
    SUGGEST,
    ENRICHMENT,
    OCR,
}

/**
 * One recorded AI request in the spend ledger, which this device keeps per library in its
 * `device.db`: which endpoint and model answered, what it was asked to do, the token usage the
 * provider reported (or this device estimated when the provider reported none), the cost worked
 * out from [AiPrices], and which run it belonged to.
 *
 * It deliberately carries no prompt, document text or answer: the ledger says what was spent, not
 * what was said.
 */
data class AiUsageRecord(
    val provider: String,
    val model: String,
    val kind: AiUsageKind,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val estimatedCostUsd: Double?,
    /** True when at least one token count is an estimate rather than the provider's own number. */
    val estimated: Boolean,
    val recordedAt: Long,
    val runId: String?,
)

/** One provider, model and kind of work's recorded requests, as the ledger aggregates them. */
data class AiUsageTotals(
    val provider: String,
    val model: String,
    /** What the requests were for; null for a kind a newer build recorded. */
    val kind: AiUsageKind?,
    val requests: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    /** Sum of the priced requests' cost; null when no request on this line had a known price. */
    val estimatedCostUsd: Double?,
    /** Requests whose model has no price (or no usage at all), reported as tokens only. */
    val unpricedRequests: Int,
    /** Requests whose token counts this device estimated, as the provider reported no usage. */
    val estimatedRequests: Int,
)

/**
 * What a set of ledger lines spent: the sum of every priced request ([pricedUsd], over
 * [pricedRequests]) and how many requests had no price. One unpriced request never hides the rest:
 * the sum is a lower bound, shown as "at least" next to the unpriced count.
 */
data class AiSpendTotal(
    val pricedUsd: Double,
    val pricedRequests: Int,
    val unpricedRequests: Int,
)

fun List<AiUsageTotals>.spendTotal(): AiSpendTotal =
    AiSpendTotal(
        pricedUsd = sumOf { it.estimatedCostUsd ?: 0.0 },
        pricedRequests = sumOf { it.requests - it.unpricedRequests },
        unpricedRequests = sumOf { it.unpricedRequests },
    )

/** The spend ledger's totals: the rolling last 30 days and everything this device recorded for the library. */
data class AiSpendSummary(
    val last30Days: List<AiUsageTotals> = emptyList(),
    val allTime: List<AiUsageTotals> = emptyList(),
) {
    companion object {
        val EMPTY = AiSpendSummary()
    }
}

/** One document's contribution to a run's token estimate. */
data class AiEstimateDoc(
    /** Characters of indexed text the prompt would carry; 0 when the document has none yet. */
    val textChars: Int,
    /** Pages the document holds, when it is the vision model that will read it. */
    val pages: Int = 0,
)

/** A run's estimated input and output tokens, before a price turns them into money. */
data class AiTokenEstimate(
    val inputTokens: Long,
    val outputTokens: Long,
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

/**
 * The arithmetic behind a run's confirmation. Deliberately plain and deliberately generous:
 * four characters per token, a fixed instruction overhead, a bounded answer, and a fixed cost per
 * page image. It is a pre-flight warning, never a bill; the ledger records what actually happened.
 */
object AiEstimator {
    private const val CHARS_PER_TOKEN = 4.0

    /** System instructions and reply contract shared by every suggestion prompt. */
    private const val SUGGEST_PROMPT_TOKENS = 900

    /** Registry/vocabulary context a field adds to the system prompt. */
    private const val FIELD_CONTEXT_TOKENS = 90
    private const val TAG_CONTEXT_TOKENS = 40
    private const val CUSTOM_PROMPT_TOKENS = 120

    /** Per-document wrapper: file name, current tags, current fields. */
    private const val DOCUMENT_OVERHEAD_TOKENS = 80

    /** The answer: the JSON object, its field values and tag lists. */
    private const val SUGGEST_OUTPUT_TOKENS = 180
    private const val FIELD_OUTPUT_TOKENS = 40

    /** One page image plus its instruction, and the transcription that comes back. */
    const val OCR_INPUT_TOKENS_PER_PAGE = 1_100
    const val OCR_OUTPUT_TOKENS_PER_PAGE = 220

    fun suggest(
        documents: List<AiEstimateDoc>,
        fields: Int,
        tags: Boolean,
        tagRemovals: Boolean,
        customPrompt: Boolean,
    ): AiTokenEstimate {
        val context =
            SUGGEST_PROMPT_TOKENS +
                fields * FIELD_CONTEXT_TOKENS +
                (if (tags || tagRemovals) TAG_CONTEXT_TOKENS else 0) +
                (if (customPrompt) CUSTOM_PROMPT_TOKENS else 0)
        val input =
            documents.sumOf { doc ->
                context + DOCUMENT_OVERHEAD_TOKENS + (doc.textChars / CHARS_PER_TOKEN).toLong()
            }
        val output =
            documents.size *
                (SUGGEST_OUTPUT_TOKENS + fields * FIELD_OUTPUT_TOKENS).toLong()
        return AiTokenEstimate(input, output)
    }

    fun ocr(pages: Int): AiTokenEstimate =
        AiTokenEstimate(
            inputTokens = pages.toLong() * OCR_INPUT_TOKENS_PER_PAGE,
            outputTokens = pages.toLong() * OCR_OUTPUT_TOKENS_PER_PAGE,
        )

    /** Prompt text a chat turn carries, approximated the same way as everything else here. */
    fun chat(
        promptChars: Int,
        answerChars: Int,
    ): AiTokenEstimate =
        AiTokenEstimate(
            inputTokens = (promptChars / CHARS_PER_TOKEN).toLong(),
            outputTokens = (answerChars / CHARS_PER_TOKEN).toLong(),
        )
}

/**
 * What one Start would buy, as the confirmation shows it: the documents and requests involved, the
 * pages the vision model would read, estimated tokens and (when the model has a list price) cost,
 * and the configured per-run request cap.
 */
data class AiRunEstimate(
    val documents: Int,
    val requests: Int,
    val pages: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedCostUsd: Double?,
    val priceKnown: Boolean,
    val requestCap: Int,
) {
    /** Whether the configured cap would stop this run before its last request. */
    val exceedsCap: Boolean get() = requests > requestCap

    /**
     * This estimate at another request count, keeping its per-request shape. Used when a background
     * run covers more documents than the preview batch the token estimate was read from.
     */
    fun scaledTo(requests: Int): AiRunEstimate {
        if (requests == this.requests || this.requests <= 0) return copy(requests = requests)
        val factor = requests.toDouble() / this.requests
        return copy(
            documents = (documents * factor).toInt(),
            requests = requests,
            pages = (pages * factor).toInt(),
            inputTokens = (inputTokens * factor).toLong(),
            outputTokens = (outputTokens * factor).toLong(),
            estimatedCostUsd = estimatedCostUsd?.let { it * factor },
        )
    }

    companion object {
        val EMPTY = AiRunEstimate(0, 0, 0, 0, 0, null, priceKnown = false, requestCap = AiRunCap.DEFAULT)
    }
}

/** Bounds for [AiConfig.maxRunRequests], the number of model calls one bulk run may make. */
object AiRunCap {
    const val MIN_REQUESTS = 1
    const val MAX_REQUESTS = 10_000
    const val DEFAULT = 200
    val RANGE = MIN_REQUESTS..MAX_REQUESTS
}

/**
 * One run's shared request counter. Every path that spends on a run's behalf takes a slot before
 * asking the endpoint, so concurrent suggestion calls cannot jointly overshoot the cap, and gives it
 * back ([release]) when the call failed before any answer came: the cap counts paid calls, so a
 * 429 or a dropped connection does not cost the run a document.
 *
 * The counter lives only as long as one batch or one queued chunk. A queued run that outlives the
 * process keeps its count in its own row (`AiEditJobEntity.requestsUsed`), reserved before the
 * chunk is sent so a crash mid-call cannot replenish it, and gives back only the slots of calls
 * that failed unanswered.
 */
class AiRunBudget(
    val cap: Int,
    initiallyUsed: Int = 0,
) {
    private val mutex = Mutex()
    private var used = initiallyUsed.coerceAtLeast(0)

    /** Requests taken so far and not given back. */
    suspend fun usedRequests(): Int = mutex.withLock { used }

    /** Take one request slot, or false when the run has reached its cap. */
    suspend fun tryAcquire(): Boolean =
        mutex.withLock {
            if (used >= cap) {
                false
            } else {
                used++
                true
            }
        }

    /** Give back a slot [tryAcquire] took for a call that failed before any answer arrived. */
    suspend fun release() {
        mutex.withLock { if (used > 0) used-- }
    }
}

/**
 * The spend ledger this device keeps for one library (it lives in that library's `device.db`, as
 * the AI configuration it records under is device-wide). Implementations are main-safe and never throw for a storage
 * failure the caller could do nothing about: recording is best effort, and the caller's AI call has
 * already succeeded by the time it is recorded.
 */
interface AiLedger {
    /** Record one request. A failure here must not fail the AI call that produced it. */
    suspend fun record(record: AiUsageRecord)

    /** Totals since [sinceMillis] (inclusive), or everything when null, per provider, model and kind. */
    suspend fun totals(sinceMillis: Long?): List<AiUsageTotals>

    /** Delete every recorded request. */
    suspend fun reset()

    companion object {
        val None: AiLedger =
            object : AiLedger {
                override suspend fun record(record: AiUsageRecord) = Unit

                override suspend fun totals(sinceMillis: Long?): List<AiUsageTotals> = emptyList()

                override suspend fun reset() = Unit
            }
    }
}
