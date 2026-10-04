package dev.npn.core.ocr

/**
 * Well-known OCR engine names and their search priority. Multiple OCR versions of the same
 * document co-exist as separate sidecars (one per device+engine); the highest-ranked version
 * is indexed for search unless the user overrides the engine per document
 * (see [dev.npn.core.model.SystemProperty.OCR_ENGINE]).
 *
 * Ranking: AI > MLKit > Tesseract > embedded text fallback > unknown. Legacy sidecars whose
 * engine label is "mixed" (or any future engine this build does not know) rank lowest, so any
 * known engine supersedes them.
 */
object OcrEngines {
    /**
     * The LLM vision engine ([dev.npn.core.ai.AiFacadeImpl.ocrEngine]). Ranked above every local engine
     * because it reads what they cannot, which is also why [OcrEngineSelector] — not this ranking
     * — decides when it is allowed to run.
     */
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

    private val ranks =
        mapOf(
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

/**
 * The engines this build knows, as a closed vocabulary. [column] is the wire/column spelling
 * shared with [OcrEngines] and the sidecar filename suffix; the enum is what crosses the
 * `dev.npn.core.app` read seam, so a new engine is a compile error at every UI site that labels
 * one rather than a raw token shipped to users.
 */
enum class OcrEngineId(
    val column: String,
) {
    AI(OcrEngines.AI),
    MLKIT(OcrEngines.MLKIT),
    TESSERACT(OcrEngines.TESSERACT),
    EMBEDDED(OcrEngines.EMBEDDED),
    ;

    companion object {
        private val byColumn = entries.associateBy { it.column }

        /**
         * Decode an engine column/sidecar label. Unknown labels — legacy "mixed" sidecars, or an
         * engine only a newer build writes — decode to null (i.e. "no known engine") rather than
         * throwing: the index is rebuildable, but a stale row must never crash a read.
         */
        fun fromColumn(value: String?): OcrEngineId? = byColumn[value]
    }
}

/**
 * An engine label as actually stored on a document or a sidecar. Three states, not two: absent is
 * `null`, a label this build has an [OcrEngineId] for is [Known], and anything else — a legacy
 * "mixed" sidecar, an engine only a newer build writes — is [Unknown] and keeps its raw label.
 *
 * The distinction matters: collapsing [Unknown] into `null` would make an indexed document look
 * un-indexed and a pinned engine look unpinned, and would lose the label needed to round-trip that
 * pin. [label] always round-trips: `of(ref.label) == ref`.
 */
sealed interface OcrEngineRef {
    val label: String

    data class Known(
        val id: OcrEngineId,
    ) : OcrEngineRef {
        override val label: String get() = id.column
    }

    data class Unknown(
        override val label: String,
    ) : OcrEngineRef

    companion object {
        fun of(label: String): OcrEngineRef = OcrEngineId.fromColumn(label)?.let(::Known) ?: Unknown(label)

        /** Column reader: null and blank both mean "no engine", anything else is a real label. */
        fun ofColumn(value: String?): OcrEngineRef? = value?.takeIf { it.isNotBlank() }?.let(::of)
    }
}
