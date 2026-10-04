package dev.npn.core.app

import kotlinx.coroutines.flow.Flow

/** Per-document OCR management: re-runs, stored versions, and the engine pin. */
interface OcrFacade {
    val ocrStatus: Flow<OcrStatus>

    /**
     * Re-queue a single document for OCR (resets its state to pending and kicks the worker). This
     * device's engine runs again whenever it ranks at least as high as the indexed version; a run
     * that reads different text is published as a new version and, being the newest of its engine,
     * becomes the indexed one. A run that reads the same text publishes nothing.
     */
    suspend fun reOcr(docId: String)

    /**
     * What offering [ocrWithAi] for [docId] would mean right now — see [AiOcrOffer].
     *
     * Per document rather than global, and one answer rather than several, so a caller deciding
     * whether (and how) to offer the action never has to know the page threshold or apply it
     * itself. Suspending and unobserved: callers ask when they are about to offer the action,
     * because the answer comes from device-local settings rather than from anything this session
     * owns.
     */
    suspend fun aiOcrOffer(docId: String): AiOcrOffer

    /**
     * Transcribe [docId] with the AI (vision-model) engine, for a scan the local engine read badly.
     *
     * Pins the document to the `ai` engine and queues an OCR run marked as asked for on this
     * device. The two are separate on purpose. The pin is a journal event: it reaches every replica
     * and makes the resulting `ai` sidecar the version indexed for search there too. The request
     * to pay for that sidecar stays here, on this device's queue row, so another replica with an
     * endpoint of its own does not pay for a second transcription — it waits for this one's, unless
     * AI is its own automatic engine. Clear the pin like any other with [setOcrEngine] — and note
     * that pinning any *other* engine is how a document opts out of automatic AI OCR
     * (`AiConfig.ocrAuto`).
     *
     * Costs one model call per page, so it is never automatic unless the user made it so. Runs on
     * a document of any length: size is a warning for the caller to deliver
     * ([AiOcrOffer.Large]), not a refusal, because a long handwritten document is exactly what the
     * vision model is for. Without a configured endpoint it changes nothing and answers
     * [AiOcrOutcome.NO_ENDPOINT], so a caller that skipped [aiOcrOffer] can say why.
     *
     * Does nothing visible for a document whose every page already has an embedded text layer: the
     * pipeline keeps that text rather than re-transcribing it, exactly as [reOcr] does.
     */
    suspend fun ocrWithAi(docId: String): AiOcrOutcome

    /** All stored OCR versions of [docId], best-ranked first. */
    suspend fun ocrVersions(docId: String): List<OcrVersion>

    /**
     * Pin (or clear, when [engine] is null) the OCR engine used for search on [docId], then
     * immediately re-select and re-index the matching stored version on this device. The pin
     * itself syncs via the journal; other devices re-select on ingesting the event.
     */
    suspend fun setOcrEngine(
        docId: String,
        engine: String?,
    )
}

/**
 * How much text recognition this device still owes, for the library's progress indicator.
 *
 * [running] is not derivable from [pending]: work can be owed with nothing draining it — before
 * the first kick, or while a document is being retried after a backoff — and a queue that claimed
 * to be running whenever it was non-empty could never show a stall.
 *
 * @param pending documents owed a pass, including the one being worked on. Excludes documents
 *  given up on after the attempt cap; those are failures, not outstanding work.
 * @param running whether a document is actually being processed right now.
 */
data class OcrStatus(
    val pending: Int,
    val running: Boolean,
)

/**
 * Whether to offer "read text with the assistant" for one document, and what to say when offering
 * it. Three states rather than a boolean, because the interesting case is neither yes nor no: the
 * action is available, and it is going to cost more than the user probably assumes.
 *
 * Only [Unavailable] hides the action. [OcrFacade.ocrWithAi] accepts the other two alike; the
 * difference is entirely in what the caller shows first.
 */
sealed interface AiOcrOffer {
    /** No AI endpoint configured on this device. Do not offer the action at all. */
    data object Unavailable : AiOcrOffer

    /** A document of ordinary length. Offer the action plainly. */
    data object Ready : AiOcrOffer

    /**
     * A long document: [pages] pages is [pages] model calls, so warn before spending them. Still
     * offered, and still runs — refusing would deny the vision model to the scanned ledgers and
     * handwritten notebooks it is most useful for.
     */
    data class Large(
        val pages: Int,
    ) : AiOcrOffer
}

/** What [OcrFacade.ocrWithAi] did with the request. */
enum class AiOcrOutcome {
    /** Pinned to the AI engine and queued on this device. */
    QUEUED,

    /** The document is not in the library; nothing changed. */
    DOCUMENT_UNAVAILABLE,

    /** No AI endpoint is configured on this device; nothing changed. */
    NO_ENDPOINT,
}

/**
 * One stored OCR version of a document (an OCR sidecar). [active] marks the version whose
 * text is currently indexed for search on this device.
 */
data class OcrVersion(
    val engine: String,
    val deviceId: String,
    val lang: String,
    val createdAt: Long,
    val active: Boolean,
)
