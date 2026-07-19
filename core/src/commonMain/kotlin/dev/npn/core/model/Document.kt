package dev.npn.core.model

import kotlinx.serialization.Serializable

/** Document identity: SHA-256 of file bytes, lowercase hex. Stable forever because documents are immutable. */
@Serializable
@JvmInline
value class DocId(val sha256: String) {
    init {
        require(sha256.length == 64) { "DocId must be a sha256 hex string" }
    }
    override fun toString(): String = sha256
}

/**
 * Library-wide review workflow, a synced setting (see [dev.npn.core.journal.JournalOp.SettingSet],
 * key [WIRE_KEY]). Determines how the review queue and the per-doc reviewed marker behave.
 *
 * - [UNTAGGED_ONLY] (default): no manual marker. A document "needs review" while it has no tags;
 *   the stored `reviewed` flag is ignored and reviewed is derived as (has any tag).
 * - [INBOX_WORKFLOW]: an explicit per-doc `reviewed` flag. A document "needs review" while it is
 *   unreviewed or untagged. Users mark documents reviewed by hand.
 */
enum class ReviewMode(val wire: String) {
    UNTAGGED_ONLY("untagged-only"),
    INBOX_WORKFLOW("inbox-workflow");

    companion object {
        const val WIRE_KEY = "reviewMode"
        val DEFAULT = UNTAGGED_ONLY
        fun fromWire(value: String?): ReviewMode =
            entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}

/** Single-value document properties settable via prop-set journal events. */
enum class DocProperty(val wire: String) {
    REVIEWED("reviewed"),            // "true"/"false"; per-doc marker for INBOX_WORKFLOW
    CORRESPONDENT("correspondent"),
    DOC_TYPE("docType"),
    DOC_DATE("docDate"), // ISO-8601 local date, e.g. 2026-07-15
    TITLE("title"),
    /**
     * User override of the OCR version used for search: an engine name (e.g. "tesseract",
     * "mlkit", "ai"), or cleared (null) for automatic best-engine selection. Synced like any
     * other LWW property; devices re-select the indexed sidecar when it changes.
     */
    OCR_ENGINE("ocrEngine");

    companion object {
        fun fromWire(value: String?): DocProperty? = entries.firstOrNull { it.wire == value }
    }
}
