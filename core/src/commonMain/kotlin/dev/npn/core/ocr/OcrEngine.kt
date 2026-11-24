package dev.npn.core.ocr

/**
 * One text-recognition engine this device can run: MLKit on Android, the Tesseract CLI on desktop,
 * or the LLM vision engine (`dev.npn.core.ai.AiFacadeImpl.ocrEngine`) when an AI endpoint is configured.
 *
 * Engines are interchangeable and ranked against each other by [OcrEngineId.rank]; which one a
 * given document's run actually uses is [OcrPipeline]'s decision, not the engine's.
 */
interface OcrEngine {
    /** Engine identifier for the sidecar `engine` field: an [OcrEngineId.column]. */
    val name: String

    /**
     * Whether this engine can actually run right now: desktop tesseract on PATH, android's bundled
     * model (always), an AI endpoint configured with a model.
     *
     * Suspending because availability is not always a local, constant fact. The AI engine's answer
     * comes from device-local settings the user can change while the app runs, so a plain `val`
     * would have to be a cache kept fresh by convention — exactly the kind of thing this codebase
     * puts in the type instead.
     */
    suspend fun available(): Boolean

    /**
     * The languages a run asked to recognise [lang] actually reads, in the same `+`-joined form:
     * the sidecar records these, so it never claims a language the engine skipped. By default all
     * of them; Tesseract drops the ones whose model is not installed.
     */
    suspend fun languagesFor(lang: String): String = lang

    /**
     * Recognize text in a PNG image for [lang] (e.g. "eng"). Returns the recognized text.
     *
     * [runId] names the run this page belongs to for the spend ledger, when the caller has one (the
     * OCR pipeline passes the document hash). A plain local engine ignores it.
     */
    suspend fun recognizePng(
        png: ByteArray,
        lang: String,
        runId: String? = null,
    ): String
}
