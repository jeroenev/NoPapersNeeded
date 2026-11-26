package dev.npn.core.ai

/**
 * The spending limit on automatic AI: [AiConfig.unattendedLimitUsd] whole US dollars over the
 * rolling 30 days the settings' spend totals show (`AI_SPEND_WINDOW_MS`).
 *
 * Rolling 30 days rather than a calendar month or a day: it is the window the user already reads
 * in the spend totals, so the limit and the number it compares against are the same figure, and a
 * day is too short for unattended work that arrives in bursts (a scanning afternoon would hit a
 * daily limit, then the limit would forget it overnight). A rolling window also never resets all
 * at once on the first of the month and releases a backlog in one go.
 *
 * Every priced request in the window counts, the ones the user started too: the limit is a budget
 * for the endpoint, of which only the unattended part can be held back. Requests without a known
 * price cannot count, which the setting's help says.
 */
object AiSpendLimit {
    const val DEFAULT_USD = 10
    const val MAX_USD = 10_000

    /** 0 means no limit. */
    val RANGE = 0..MAX_USD

    /** Whether [last30Days] spent [limitUsd] or more; never with no limit. */
    fun reached(
        last30Days: List<AiUsageTotals>,
        limitUsd: Int,
    ): Boolean = limitUsd > 0 && last30Days.spendTotal().pricedUsd >= limitUsd
}

/**
 * Whether this replica may spend the user's AI budget *without being asked*.
 *
 * Two paths in the app call a paid endpoint with nobody watching: automatic vision OCR
 * (`OcrEngineSelector`, one call per page of every document this replica OCRs) and automatic
 * enrichment (`AiAutoEnricher`, one call per document it just OCR'd). This is the one home for
 * that decision, so neither reads its toggle straight off [AiConfig].
 *
 * The answer is the user's own [AiConfig.ocrAuto] / [AiConfig.autoOnOcr] toggles, only while the
 * endpoint is configured, and only while [AiSpendLimit] is not reached ([paused]). A paused
 * replica reads new documents with its local engine and keeps queueing enrichment without draining
 * it, so nothing is lost: the queue drains again once the 30-day spend drops below the limit or the
 * user raises it. A run the user asked for (a queued edit run, "OCR with AI" on one document) does
 * not consult it on the device it was asked on; a replica that syncs the resulting engine pin was
 * asked nothing, so there it is unattended work like any other.
 *
 * Every answer is read per call, so flipping a setting takes effect on the next document.
 */
internal class UnattendedAiPolicy(
    /** This library's ledger totals over the last 30 days; nothing spent unless a session reads them. */
    private val spentLast30Days: suspend () -> List<AiUsageTotals> = { emptyList() },
    private val readConfig: suspend () -> AiConfig,
) {
    /**
     * May the vision model be this replica's automatic OCR engine? The engine's own
     * availability (`OcrEngine.available`) is checked by the selector; this is purely the
     * permission to spend.
     */
    suspend fun autoOcr(): Boolean = readConfig().let { it.configured && it.ocrAuto && !reached(it) }

    /**
     * Should a document this replica just OCR'd be queued for enrichment (tags, empty fields)
     * without review? Ignores the limit, so a document OCR'd while it holds is still enriched later.
     */
    suspend fun autoEnrich(): Boolean = readConfig().let { it.configured && it.autoOnOcr }

    /** May the enrichment queue spend now: [autoEnrich], and the limit not reached. */
    suspend fun mayEnrichNow(): Boolean = readConfig().let { it.configured && it.autoOnOcr && !reached(it) }

    /** Whether the spending limit holds automatic AI back right now. */
    suspend fun paused(): Boolean = reached(readConfig())

    private suspend fun reached(config: AiConfig): Boolean =
        AiSpendLimit.reached(spentLast30Days(), config.unattendedLimitUsd)
}
