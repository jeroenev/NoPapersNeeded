package dev.npn.core.ocr

/**
 * One text-recognition engine this device can run: MLKit on Android, the Tesseract CLI on desktop,
 * or the LLM vision engine (`dev.npn.core.ai.AiOcrEngine`) when an AI endpoint is configured.
 *
 * Engines are interchangeable and ranked against each other by [OcrEngines.rank]; which one a
 * given document's run actually uses is [OcrPipeline]'s decision, not the engine's.
 */
interface OcrEngine {
    /** Engine identifier for the sidecar `engine` field, one of [OcrEngines.known]. */
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

    /** Recognize text in a PNG image for [lang] (e.g. "eng"). Returns the recognized text. */
    suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String
}
