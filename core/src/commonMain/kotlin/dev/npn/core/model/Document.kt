package dev.npn.core.model

/**
 * Library-wide review workflow, a synced setting (see [dev.npn.core.journal.JournalOp.SettingSet],
 * key `reviewMode`, `LibrarySettingKeys.REVIEW_MODE`). Determines how the review queue and the per-doc reviewed marker behave.
 *
 * - [UNTAGGED_ONLY] (default): no manual marker. A document "needs review" while it has no tags;
 *   the stored `reviewed` flag is ignored and reviewed is derived as (has any tag).
 * - [INBOX_WORKFLOW]: an explicit per-doc `reviewed` flag. A document "needs review" while it is
 *   unreviewed or untagged. Users mark documents reviewed by hand.
 */
enum class ReviewMode(
    val wire: String,
) {
    UNTAGGED_ONLY("untagged-only"),
    INBOX_WORKFLOW("inbox-workflow"),
    ;

    companion object {
        val DEFAULT = UNTAGGED_ONLY

        fun fromWire(value: String?): ReviewMode = entries.firstOrNull { it.wire == value } ?: DEFAULT
    }
}

/**
 * Whether the stored `reviewed` marker is authoritative in this review mode.
 *
 * The one place the rule is spelled out; every screen asks this instead of comparing the mode. In
 * [ReviewMode.INBOX_WORKFLOW] the user maintains an explicit reviewed flag, so the stored marker is
 * real: the UI shows a reviewed badge, offers a reviewed filter and the mark-reviewed actions, and
 * counts the review queue. In [ReviewMode.UNTAGGED_ONLY] review state is derived from tags
 * (reviewed == tagged) and the stored marker is stale, so all of those are hidden — surfacing a
 * stale flag would lie to the user. Callers that need the derived value use
 * [DocListItem.effectiveReviewed].
 *
 * Deliberately an exhaustive `when` rather than an `== INBOX_WORKFLOW` comparison: a new
 * [ReviewMode] must fail to compile here and be given an explicit answer, instead of silently
 * inheriting [ReviewMode.UNTAGGED_ONLY]'s derived-from-tags semantics. Check
 * [DocListItem.effectiveReviewed] too when adding one — a third mode may need a third derivation
 * rather than one of these two.
 */
val ReviewMode.reviewMarkerIsAuthoritative: Boolean
    get() =
        when (this) {
            ReviewMode.INBOX_WORKFLOW -> true
            ReviewMode.UNTAGGED_ONLY -> false
        }

/**
 * Per-document system state settable via `prop-set` journal events. User-authored metadata (title,
 * correspondent, date, notes, anything the user adds) is NOT here: it is a registry field
 * ([FieldDef]) written as `field-set`. These properties describe app-owned state.
 */
enum class SystemProperty(
    val wire: String,
) {
    /** "true"/"false"; per-doc marker for [ReviewMode.INBOX_WORKFLOW]. */
    REVIEWED("reviewed"),

    /**
     * User override of the OCR version used for search: an engine name (e.g. "tesseract",
     * "mlkit", "ai"), or cleared (null) for automatic best-engine selection. Synced like any
     * other LWW property; devices re-select the indexed sidecar when it changes.
     */
    OCR_ENGINE("ocrEngine"),

    /** Intended library-relative PDF location; LWW by document hash, independent of local availability. */
    STORAGE_PATH("storagePath"),
    ;

    companion object {
        fun fromWire(value: String?): SystemProperty? = entries.firstOrNull { it.wire == value }
    }
}
