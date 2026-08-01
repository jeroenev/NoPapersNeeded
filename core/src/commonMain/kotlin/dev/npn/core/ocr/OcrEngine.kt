package dev.npn.core.ocr

/**
 * On-device OCR engine: MLKit text recognition on Android and a Tesseract CLI on desktop when the
 * binary is present. The interface also allows Android builds to provide a different engine.
 */
interface OcrEngine {
    /** True when this engine can actually run (desktop: tesseract on PATH; android: bundled). */
    val available: Boolean

    /** Engine identifier for the sidecar `engine` field: "mlkit" | "tesseract". */
    val name: String

    /** Recognize text in a PNG image for [lang] (e.g. "eng"). Returns the recognized text. */
    suspend fun recognizePng(
        png: ByteArray,
        lang: String,
    ): String
}
