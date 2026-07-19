package dev.npn.core.ocr

/**
 * Well-known OCR engine names and their search priority. Multiple OCR versions of the same
 * document co-exist as separate sidecars (one per device+engine); the highest-ranked version
 * is indexed for search unless the user overrides the engine per document
 * (see [dev.npn.core.model.DocProperty.OCR_ENGINE]).
 *
 * Ranking: AI > MLKit > Tesseract > embedded text fallback > unknown. Legacy sidecars whose
 * engine label is "mixed" (or any future engine this build does not know) rank lowest, so any
 * known engine supersedes them.
 */
object OcrEngines {
    /** Reserved for a future AI-based engine; already ranked so its sidecars win on arrival. */
    const val AI = "ai"
    const val MLKIT = "mlkit"
    const val TESSERACT = "tesseract"

    /** Not a real engine: the document's own text layer, extracted without OCR. */
    const val EMBEDDED = "embedded"

    private const val RANK_AI = 4
    private const val RANK_MLKIT = 3
    private const val RANK_TESSERACT = 2
    private const val RANK_EMBEDDED = 1
    const val RANK_UNKNOWN = 0

    private val ranks = mapOf(
        AI to RANK_AI,
        MLKIT to RANK_MLKIT,
        TESSERACT to RANK_TESSERACT,
        EMBEDDED to RANK_EMBEDDED,
    )

    /** Engines that may appear as a filename suffix (`<hash>.<device>.<engine>.json`). */
    val known: Set<String> = ranks.keys

    fun rank(engine: String?): Int = ranks[engine] ?: RANK_UNKNOWN

    fun isKnown(engine: String): Boolean = engine in ranks
}
