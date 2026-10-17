package dev.npn.core.ocr

/**
 * Where a document stands in the OCR pipeline, as a closed vocabulary and the one source of its
 * names. [column] is the spelling stored in `documents.ocrState` (a plain TEXT column); the enum is
 * what crosses the `dev.npn.core.app` read seam, so adding a state is a compile error at every UI
 * site that labels one rather than a raw token shipped to users.
 */
enum class OcrState(
    val column: String,
) {
    /** Never processed: no sidecar on any device and nothing queued here. */
    NONE("none"),

    /** Queued for this device's engine. */
    PENDING("pending"),

    /** This device produced the indexed text. */
    DONE("done"),

    /** This device tried and gave up after [OcrPipeline.MAX_ATTEMPTS]. */
    FAILED("failed"),

    /** Indexed text came from another device's sidecar, not from a run here. */
    ADOPTED("adopted"),
    ;

    companion object {
        private val byColumn = entries.associateBy { it.column }

        /**
         * Decode an `ocrState` column value. Unknown values — a row written by a newer build —
         * decode to [NONE] rather than throwing: the index is rebuildable, but a stale row must
         * never crash a read.
         */
        fun fromColumn(value: String?): OcrState = byColumn[value] ?: NONE
    }
}
