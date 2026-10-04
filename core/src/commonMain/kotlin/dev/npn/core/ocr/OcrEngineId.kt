package dev.npn.core.ocr

/**
 * The OCR engines this build knows, as a closed vocabulary and the one source of their names.
 * [column] is the spelling everywhere an engine is stored: the sidecar's `engine` field and filename
 * suffix (`<hash>.<device>.<engine>.json`), `documents.ocrEngine` and the engine pin. The enum is
 * what crosses the `dev.npn.core.app` read seam, so a new engine is a compile error at every UI site
 * that labels one rather than a raw token shipped to users.
 *
 * Multiple OCR versions of the same document co-exist as separate sidecars (one per device+engine);
 * the highest-[rank]ed version is indexed for search unless the user pins an engine per document
 * (see [dev.npn.core.model.SystemProperty.OCR_ENGINE]).
 */
enum class OcrEngineId(
    val column: String,
    /** Search priority: AI > MLKit > Tesseract > embedded text > any engine this build does not know. */
    val rank: Int,
) {
    /**
     * The LLM vision engine ([dev.npn.core.ai.AiFacadeImpl.ocrEngine]). Ranked above every local
     * engine because it reads what they cannot, which is also why [OcrEngineSelector] — not this
     * ranking — decides when it is allowed to run.
     */
    AI("ai", 4),
    MLKIT("mlkit", 3),
    TESSERACT("tesseract", 2),

    /** Not a real engine: the document's own text layer, extracted without OCR. */
    EMBEDDED("embedded", 1),
    ;

    companion object {
        /**
         * The rank of a label no entry has: legacy sidecars labelled "mixed", or an engine only a
         * newer build writes. Lowest, so any known engine supersedes them.
         */
        const val RANK_UNKNOWN = 0

        private val byColumn = entries.associateBy { it.column }

        /**
         * Decode an engine column/sidecar label. Unknown labels — legacy "mixed" sidecars, or an
         * engine only a newer build writes — decode to null (i.e. "no known engine") rather than
         * throwing: the index is rebuildable, but a stale row must never crash a read.
         */
        fun fromColumn(value: String?): OcrEngineId? = byColumn[value]

        /** The search priority of a stored engine label ([RANK_UNKNOWN] for one this build does not know). */
        fun rank(label: String?): Int = fromColumn(label)?.rank ?: RANK_UNKNOWN
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
