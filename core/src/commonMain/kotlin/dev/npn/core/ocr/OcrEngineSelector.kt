package dev.npn.core.ocr

/**
 * Which engine a document's OCR run may use on this device.
 *
 * The vision engine outranks every local engine ([OcrEngineId.rank]), so the pipeline's "run the
 * best engine available" would turn a configured endpoint into a paid pass over the whole library.
 * This is the rule that prevents it, the one piece of the OCR path that spends the user's money.
 *
 * @param local the free on-device engine (MLKit / Tesseract); always eligible.
 * @param ai the vision engine, when the build wires one at all.
 * @param largeDocumentPages the page count above which [ai] never runs *unattended* (one call per
 *  page makes a long scan the one input that can run up an unexpected bill); such a document is
 *  OCR'd by [local]. An asked-for run ignores it: `OcrFacade.aiOcrOffer` warns the user first.
 * @param aiAuto whether [ai] may be this replica's automatic engine right now, from
 *  `dev.npn.core.ai.UnattendedAiPolicy`; this selector adds only the per-document rule on top.
 *  Read per call, so a change takes effect on the next document.
 */
class OcrEngineSelector(
    private val local: OcrEngine,
    private val ai: OcrEngine? = null,
    val largeDocumentPages: Int = DEFAULT_LARGE_DOCUMENT_PAGES,
    private val aiAuto: suspend () -> Boolean = { false },
) {
    /** True when a vision endpoint is configured, i.e. an AI run can be asked for at all. */
    suspend fun aiAvailable(): Boolean = ai?.available() == true

    /** True when [pageCount] is past [largeDocumentPages], i.e. an AI run is worth warning about. */
    fun isLargeForAi(pageCount: Int): Boolean = pageCount > largeDocumentPages

    /**
     * The engine to run for a document currently pinned to [pinnedEngine]
     * (`DocumentEntity.ocrEngineOverride`, null when unpinned) with [pageCount] pages (null when
     * nothing has opened the PDF yet). [aiRequested] says the user asked for this document's AI
     * run on this device (`OcrQueueEntity.aiRequested`, set by `OcrFacade.ocrWithAi`).
     *
     * The pin alone is not a request: it is a journal event that reaches every replica, and means
     * "search this document's `ai` text". So a pin to `ai` without a request here is unattended
     * work, run only through [aiAuto] and under [largeDocumentPages]; otherwise this device waits
     * for the asking device's sidecar. A pin to any other engine wins over [aiAuto], since a
     * transcription it would pay for would lose the selection anyway. `OcrPipeline.canImprove`
     * applies the same rule, so a document is never queued for an engine its run then declines.
     */
    suspend fun forDocument(
        pinnedEngine: String?,
        pageCount: Int?,
        aiRequested: Boolean,
    ): OcrEngine {
        val ai = ai ?: return local
        val wanted =
            when {
                pinnedEngine != null && pinnedEngine != OcrEngineId.AI.column -> false
                pinnedEngine == OcrEngineId.AI.column && aiRequested -> true
                pageCount != null && isLargeForAi(pageCount) -> false
                else -> aiAuto()
            }
        return if (wanted && ai.available()) ai else local
    }

    companion object {
        /**
         * Default "large document" threshold: invoices, letters and statements run a handful of
         * pages, so it only bites on manuals and scanned books, where a per-page bill adds up.
         */
        const val DEFAULT_LARGE_DOCUMENT_PAGES = 20
    }
}
