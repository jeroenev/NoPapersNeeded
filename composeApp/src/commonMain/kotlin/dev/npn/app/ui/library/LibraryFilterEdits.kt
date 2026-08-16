package dev.npn.app.ui.library

import dev.npn.core.app.LibraryFilter

/**
 * Which of the three tag buckets a tag currently sits in, or [OFF] when it is not filtered on.
 *
 * A [LibraryFilter] keeps three parallel tag lists — [LibraryFilter.tagsAll] (AND),
 * [LibraryFilter.tagsAny] (OR) and [LibraryFilter.tagsNone] (NOT). A single tag must live in at
 * most ONE of them: a filter that asks for "AND foo" and "NOT foo" at once is contradictory. This
 * enum names the four resting states a tag can be in so both the edit side (cycling a chip) and the
 * display side (the removable chips) speak the same vocabulary instead of re-deriving it from the
 * raw lists.
 */
enum class TagBucket { OFF, ALL, ANY, NONE }

/**
 * The bucket [tag] currently sits in within [filter].
 *
 * The lists are checked in a fixed order (ALL, then ANY, then NONE); because [cycleTag] guarantees
 * a tag is only ever in one bucket, the order only matters for a hand-corrupted filter, where the
 * first match wins deterministically.
 */
fun tagBucket(
    filter: LibraryFilter,
    tag: String,
): TagBucket =
    when (tag) {
        in filter.tagsAll -> TagBucket.ALL
        in filter.tagsAny -> TagBucket.ANY
        in filter.tagsNone -> TagBucket.NONE
        else -> TagBucket.OFF
    }

/**
 * Advance [tag] one step around the bucket cycle OFF -> ALL -> ANY -> NONE -> OFF and return the
 * updated filter.
 *
 * Load-bearing invariant: the tag is stripped from ALL THREE buckets first, then re-added to its
 * next bucket (or, on the NONE -> OFF step, left out entirely). Without the strip-first step a tag
 * could end up in two buckets at once — e.g. cycling from ALL would append to ANY while the ALL
 * entry lingered — producing a contradictory filter. The strip is unconditional (cheap list
 * subtractions that no-op when the tag is absent) so the single move is always "clear, then place".
 */
fun cycleTag(
    filter: LibraryFilter,
    tag: String,
): LibraryFilter {
    val stripped =
        filter.copy(
            tagsAll = filter.tagsAll - tag,
            tagsAny = filter.tagsAny - tag,
            tagsNone = filter.tagsNone - tag,
        )
    return when (tagBucket(filter, tag)) {
        TagBucket.OFF -> stripped.copy(tagsAll = stripped.tagsAll + tag)
        TagBucket.ALL -> stripped.copy(tagsAny = stripped.tagsAny + tag)
        TagBucket.ANY -> stripped.copy(tagsNone = stripped.tagsNone + tag)
        TagBucket.NONE -> stripped
    }
}

/**
 * One removable filter chip: what facet it stands for, so the display layer can render a localized
 * label and know how to clear it, without re-encoding the filter's shape.
 *
 * Each variant carries only the data the label needs. Removal is NOT a closure here — the pure
 * [FilterChip.remove] does the clearing so the "which field does this chip clear" rule is asserted
 * in tests rather than buried in a composable's `buildList`. The display side matches on the
 * variant purely to pick a `stringResource`.
 */
sealed interface FilterChip {
    /** A tag in the AND bucket ([LibraryFilter.tagsAll]). */
    data class TagAll(
        val tag: String,
    ) : FilterChip

    /** A tag in the OR bucket ([LibraryFilter.tagsAny]). */
    data class TagAny(
        val tag: String,
    ) : FilterChip

    /** A tag in the NOT bucket ([LibraryFilter.tagsNone]). */
    data class TagNone(
        val tag: String,
    ) : FilterChip

    /** The reviewed marker; [reviewed] is the value being filtered on (true = reviewed). */
    data class Reviewed(
        val reviewed: Boolean,
    ) : FilterChip

    /** A correspondent equality filter. */
    data class Correspondent(
        val value: String,
    ) : FilterChip

    /** A document-type equality filter. */
    data class DocType(
        val value: String,
    ) : FilterChip

    /** Lower bound of the document-date range (ISO yyyy-MM-dd). */
    data class DocDateFrom(
        val value: String,
    ) : FilterChip

    /** Upper bound of the document-date range (ISO yyyy-MM-dd). */
    data class DocDateTo(
        val value: String,
    ) : FilterChip

    /** The "untagged only" toggle. */
    data object UntaggedOnly : FilterChip

    /** The INBOX_WORKFLOW "review queue" preset (unreviewed OR untagged). */
    data object ReviewQueue : FilterChip
}

/**
 * The ordered list of removable chips for [filter]'s active facets.
 *
 * This is the single definition of WHICH facets surface as chips and IN WHAT ORDER — tags first
 * (AND, then OR, then NOT, each in the filter's own list order), then reviewed, correspondent, doc
 * type, the date bounds, and finally the two review-scope toggles. It re-uses [TagBucket] semantics
 * so the chip list can never disagree with what [cycleTag] produced. Empty facets contribute no
 * chip, so an empty filter yields an empty list.
 */
fun LibraryFilter.activeChips(): List<FilterChip> =
    buildList {
        tagsAll.forEach { add(FilterChip.TagAll(it)) }
        tagsAny.forEach { add(FilterChip.TagAny(it)) }
        tagsNone.forEach { add(FilterChip.TagNone(it)) }
        reviewed?.let { add(FilterChip.Reviewed(it)) }
        correspondent?.let { add(FilterChip.Correspondent(it)) }
        docType?.let { add(FilterChip.DocType(it)) }
        docDateFrom?.let { add(FilterChip.DocDateFrom(it)) }
        docDateTo?.let { add(FilterChip.DocDateTo(it)) }
        if (untaggedOnly) add(FilterChip.UntaggedOnly)
        if (unreviewedOrUntagged) add(FilterChip.ReviewQueue)
    }

/**
 * Clear the single facet [chip] stands for from [filter] and return the updated filter.
 *
 * The inverse of [activeChips]: for a tag chip it removes just that tag from just its bucket (so a
 * NOT-tag chip never touches the AND list), and for a single-value facet it nulls that field. This
 * is the assertable "removing this chip clears exactly this" rule that used to live as per-chip
 * closures inside the composable.
 */
fun LibraryFilter.removeChip(chip: FilterChip): LibraryFilter =
    when (chip) {
        is FilterChip.TagAll -> copy(tagsAll = tagsAll - chip.tag)
        is FilterChip.TagAny -> copy(tagsAny = tagsAny - chip.tag)
        is FilterChip.TagNone -> copy(tagsNone = tagsNone - chip.tag)
        is FilterChip.Reviewed -> copy(reviewed = null)
        is FilterChip.Correspondent -> copy(correspondent = null)
        is FilterChip.DocType -> copy(docType = null)
        is FilterChip.DocDateFrom -> copy(docDateFrom = null)
        is FilterChip.DocDateTo -> copy(docDateTo = null)
        FilterChip.UntaggedOnly -> copy(untaggedOnly = false)
        FilterChip.ReviewQueue -> copy(unreviewedOrUntagged = false)
    }
