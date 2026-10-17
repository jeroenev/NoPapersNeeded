package dev.npn.app.ui.library

import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.TagBucket
import dev.npn.core.app.withTag

/**
 * One removable filter chip: what facet it stands for, so the display layer can render a localized
 * label and know how to clear it, without re-encoding the filter's shape.
 *
 * Each variant carries only the data the label needs. Removal is not a closure here: the pure
 * [removeChip] does the clearing, so the "which field does this chip clear" rule is asserted in
 * tests. The display side matches on the variant purely to pick a `stringResource`.
 */
sealed interface ActiveFilterChip {
    /**
     * A restriction to an explicit set of documents ([LibraryFilter.docIds]), as handed over by the
     * AI assistant's "open all documents".
     *
     * Carries only the [count], because the ids themselves are not something a label can say and
     * removal clears the whole set anyway. Unlike every other facet this one cannot be re-created
     * from the filter sheet, so without a chip it would be an invisible restriction the user
     * cannot undo.
     */
    data class DocIds(
        val count: Int,
    ) : ActiveFilterChip

    /** A tag in the AND bucket ([LibraryFilter.tagsAll]). */
    data class TagAll(
        val tag: String,
    ) : ActiveFilterChip

    /** A tag in the OR bucket ([LibraryFilter.tagsAny]). */
    data class TagAny(
        val tag: String,
    ) : ActiveFilterChip

    /** A tag in the NOT bucket ([LibraryFilter.tagsNone]). */
    data class TagNone(
        val tag: String,
    ) : ActiveFilterChip

    /** The reviewed marker; [reviewed] is the value being filtered on (true = reviewed). */
    data class Reviewed(
        val reviewed: Boolean,
    ) : ActiveFilterChip

    /** A field equality filter (`fieldEquals[key] == value`). */
    data class FieldEquals(
        val key: String,
        val value: String,
    ) : ActiveFilterChip

    /** Lower bound of a field range, in the field's canonical form. */
    data class FieldFrom(
        val key: String,
        val value: String,
    ) : ActiveFilterChip

    /** Upper bound of a field range, in the field's canonical form. */
    data class FieldTo(
        val key: String,
        val value: String,
    ) : ActiveFilterChip

    /** The "untagged only" toggle. */
    data object UntaggedOnly : ActiveFilterChip

    /** The INBOX_WORKFLOW "review queue" preset (unreviewed OR untagged). */
    data object ReviewQueue : ActiveFilterChip
}

/**
 * The ordered list of removable chips for [filter]'s active facets.
 *
 * This is the single definition of WHICH facets surface as chips and IN WHAT ORDER — the document-set
 * restriction first (it scopes everything else), then tags (AND, then OR, then NOT, each in the
 * filter's own list order), then reviewed, field equalities, field range bounds, and finally the
 * two review-scope toggles. It re-uses [TagBucket] semantics so the chip list can never disagree
 * with what [withTag] produced. Empty facets contribute no chip, so an empty filter yields an empty
 * list.
 */
fun LibraryFilter.activeChips(): List<ActiveFilterChip> =
    buildList {
        if (docIds.isNotEmpty()) add(ActiveFilterChip.DocIds(docIds.size))
        tagsAll.forEach { add(ActiveFilterChip.TagAll(it)) }
        tagsAny.forEach { add(ActiveFilterChip.TagAny(it)) }
        tagsNone.forEach { add(ActiveFilterChip.TagNone(it)) }
        reviewed?.let { add(ActiveFilterChip.Reviewed(it)) }
        fieldEquals.forEach { (key, value) -> add(ActiveFilterChip.FieldEquals(key, value)) }
        fieldRanges.forEach { (key, range) ->
            range.from?.let { add(ActiveFilterChip.FieldFrom(key, it)) }
            range.to?.let { add(ActiveFilterChip.FieldTo(key, it)) }
        }
        if (untaggedOnly) add(ActiveFilterChip.UntaggedOnly)
        if (unreviewedOrUntagged) add(ActiveFilterChip.ReviewQueue)
    }

/**
 * Clear the single facet [chip] stands for from [filter] and return the updated filter.
 *
 * The inverse of [activeChips]: for a tag chip it removes just that tag from just its bucket (so a
 * NOT-tag chip never touches the AND list), and for a single-value facet it nulls that field.
 */
fun LibraryFilter.removeChip(chip: ActiveFilterChip): LibraryFilter =
    when (chip) {
        is ActiveFilterChip.DocIds -> copy(docIds = emptyList())
        is ActiveFilterChip.TagAll -> copy(tagsAll = tagsAll - chip.tag)
        is ActiveFilterChip.TagAny -> copy(tagsAny = tagsAny - chip.tag)
        is ActiveFilterChip.TagNone -> copy(tagsNone = tagsNone - chip.tag)
        is ActiveFilterChip.Reviewed -> copy(reviewed = null)
        is ActiveFilterChip.FieldEquals -> withFieldEquals(chip.key, null)
        is ActiveFilterChip.FieldFrom -> withFieldBounds(chip.key, from = null)
        is ActiveFilterChip.FieldTo -> withFieldBounds(chip.key, to = null)
        ActiveFilterChip.UntaggedOnly -> copy(untaggedOnly = false)
        ActiveFilterChip.ReviewQueue -> copy(unreviewedOrUntagged = false)
    }
