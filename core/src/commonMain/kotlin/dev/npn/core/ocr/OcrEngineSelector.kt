package dev.npn.core.ocr

/**
 * Which engine a document's OCR run may use on this device.
 *
 * This exists as its own type because the answer stopped being obvious. With only local engines
 * there was nothing to decide — one engine, run it. An LLM vision engine changes that: it outranks
 * every local engine ([OcrEngines.rank]), so the pipeline's own "run the best engine available"
 * instinct would quietly turn a configured endpoint into a paid pass over the whole library. The
 * rule that prevents that is small, but it is a policy, and it is the one piece of the OCR path
 * that spends the user's money — so it gets a name, one home, and its own tests.
 *
 * @param local the free on-device engine (MLKit / Tesseract); always eligible.
 * @param ai the vision engine, when the build wires one at all.
 * @param largeDocumentPages the page count above which a document is "large" for [ai]. One model
 *  call per page means a single fat scan is the one input that can turn this into a bill nobody
 *  expected, so above it [ai] never runs *unattended* — the document still gets OCR'd, just by
 *  [local]. It is not a hard ceiling: a user who asks for this document by name has seen the cost
 *  and gets what they asked for (`OcrFacade.aiOcrOffer` is what warns them).
 * @param aiAuto whether [ai] may be this replica's automatic engine right now — the OCR half of
 *  `dev.npn.core.ai.UnattendedAiPolicy`, which is where "may this replica spend unattended" is
 *  decided for OCR and enrichment alike; this selector owns only the per-document rule on top of
 *  it. Read per call rather than captured, so a change takes effect on the next document.
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
     * run on this device (`OcrQueueEntity.aiRequested`).
     *
     * The AI engine needs a configured endpoint *and* one of two deliberate reasons: the user asked
     * for this document by name, on this device (`OcrFacade.ocrWithAi`, which also pins it), or
     * made the vision model this device's automatic engine.
     *
     * The pin alone is not a request. It is a journal event, so it reaches every replica, and it
     * means "search this document's `ai` text" — a selection preference. Taken as a request, one
     * device's "OCR with AI" would have every replica with an endpoint pay for its own
     * transcription. So a pin to `ai` that arrives from elsewhere is unattended work here: it runs
     * the model only through [aiAuto] and under [largeDocumentPages], and otherwise waits for the
     * asking device's sidecar, which the pin then selects.
     *
     * A pin to *any other* engine overrides [aiAuto] rather than losing to it. The pin is the user
     * naming an engine for this document; taking it as "search this version, but still spend a
     * call per page on the one I did not ask for" would charge for a transcription that then loses
     * the selection anyway.
     *
     * [largeDocumentPages] only stops the *unattended* path: an asked-for run is a decision someone
     * made about this document with the page count in front of them, and refusing it would leave
     * the 200-page handwritten ledger — the case the vision model is most needed for — with no way
     * to be read at all. Both this and the queueing check (`OcrPipeline.canImprove`) run the same
     * rule over the same inputs, so a document is never queued for an engine the run then declines
     * to use.
     */
    suspend fun forDocument(
        pinnedEngine: String?,
        pageCount: Int?,
        aiRequested: Boolean,
    ): OcrEngine {
        val ai = ai ?: return local
        val wanted =
            when {
                pinnedEngine != null && pinnedEngine != OcrEngines.AI -> false
                pinnedEngine == OcrEngines.AI && aiRequested -> true
                pageCount != null && isLargeForAi(pageCount) -> false
                else -> aiAuto()
            }
        return if (wanted && ai.available()) ai else local
    }

    companion object {
        /**
         * Default "large document" threshold. Sized for what a personal archive actually contains:
         * invoices, letters and statements run a handful of pages, so it only bites on the outliers
         * (manuals, scanned books) where a per-page bill stops being background noise.
         */
        const val DEFAULT_LARGE_DOCUMENT_PAGES = 20
    }
}
