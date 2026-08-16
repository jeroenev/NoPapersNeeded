package dev.npn.app.ui.library

import dev.npn.core.app.DocListItem
import dev.npn.core.model.ReviewMode

/**
 * Whether the stored `reviewed` marker is authoritative in this review mode.
 *
 * The single source of the rule that used to be spelled out as `mode == INBOX_WORKFLOW` at three
 * separate call sites. In [ReviewMode.INBOX_WORKFLOW] the user maintains an explicit reviewed flag,
 * so the stored marker is real and the UI both shows a reviewed badge and offers a reviewed filter.
 * In [ReviewMode.UNTAGGED_ONLY] review state is derived from tags (reviewed == tagged) and the
 * stored marker is stale, so both the badge and the filter are hidden — surfacing a stale flag
 * would lie to the user. Callers that need the derived value use [DocListItem.effectiveReviewed].
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
 * Effective reviewed state for display. In [ReviewMode.UNTAGGED_ONLY] the marker is derived
 * (reviewed == has any tag); in [ReviewMode.INBOX_WORKFLOW] the stored reviewed flag is
 * authoritative (see [reviewMarkerIsAuthoritative]).
 */
fun DocListItem.effectiveReviewed(mode: ReviewMode): Boolean =
    if (mode.reviewMarkerIsAuthoritative) doc.reviewed else tags.isNotEmpty()
