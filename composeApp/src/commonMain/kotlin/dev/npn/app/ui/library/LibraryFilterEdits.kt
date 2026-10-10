package dev.npn.app.ui.library

import dev.npn.core.app.LibraryFilter

/**
 * Which of the three tag buckets a tag currently sits in, or [OFF] when it is not filtered on.
 *
 * A [LibraryFilter] keeps three parallel tag lists — [LibraryFilter.tagsAll] (AND),
 * [LibraryFilter.tagsAny] (OR) and [LibraryFilter.tagsNone] (NOT). A single tag must live in at
 * most ONE of them: a filter that asks for "AND foo" and "NOT foo" at once is contradictory. This
 * enum names the four resting states a tag can be in so both the edit side (picking a chip in the
 * include or exclude group) and the display side (the removable chips) speak the same vocabulary
 * instead of re-deriving it from the raw lists.
 */
enum class TagBucket { OFF, ALL, ANY, NONE }

/**
 * The bucket [tag] currently sits in within [filter].
 *
 * The lists are checked in a fixed order (ALL, then ANY, then NONE); because [withTag] guarantees
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
 * Place [tag] in [bucket], and only that bucket.
 *
 * Load-bearing invariant: the tag is stripped from ALL THREE lists first, then re-added to its
 * chosen bucket (or, for [TagBucket.OFF], left out entirely). Without the strip-first step a tag
 * could end up in two buckets at once — including and excluding the same tag — producing a
 * contradictory filter. The strip is unconditional (cheap list subtractions that no-op when the
 * tag is absent) so the single move is always "clear, then place".
 */
fun LibraryFilter.withTag(
    tag: String,
    bucket: TagBucket,
): LibraryFilter {
    val cleared = copy(tagsAll = tagsAll - tag, tagsAny = tagsAny - tag, tagsNone = tagsNone - tag)
    return when (bucket) {
        TagBucket.ALL -> cleared.copy(tagsAll = cleared.tagsAll + tag)
        TagBucket.ANY -> cleared.copy(tagsAny = cleared.tagsAny + tag)
        TagBucket.NONE -> cleared.copy(tagsNone = cleared.tagsNone + tag)
        TagBucket.OFF -> cleared
    }
}

/**
 * Move every included tag into one match mode, so "match all" and "match any" are a property of
 * the whole include group rather than of each individual tag.
 */
fun LibraryFilter.matchIncludedTags(matchAll: Boolean): LibraryFilter {
    val included = (tagsAll + tagsAny).distinct()
    return copy(tagsAll = if (matchAll) included else emptyList(), tagsAny = if (matchAll) emptyList() else included)
}

/**
 * This draft, edited over the live filter [from], once the live filter has changed to [to]: each part
 * the change touched takes its new value and every other part keeps the draft's, so an edit not
 * applied yet survives a search or a chip removed beside it. A part is one single-value facet, one
 * field's equality or range, or one tag's bucket.
 */
fun LibraryFilter.rebased(
    from: LibraryFilter,
    to: LibraryFilter,
): LibraryFilter {
    if (from == to) return this
    val draft = this

    fun <T> part(read: LibraryFilter.() -> T): T = if (from.read() == to.read()) draft.read() else to.read()
    val tags = (from.tagsAll + from.tagsAny + from.tagsNone + to.tagsAll + to.tagsAny + to.tagsNone).distinct()
    val tagged =
        tags.fold(draft) { edited, tag ->
            val now = tagBucket(to, tag)
            if (tagBucket(from, tag) == now) edited else edited.withTag(tag, now)
        }
    return tagged.copy(
        docIds = part { docIds },
        query = part { query },
        reviewed = part { reviewed },
        fieldEquals = rebasedByKey(fieldEquals, from.fieldEquals, to.fieldEquals),
        fieldRanges = rebasedByKey(fieldRanges, from.fieldRanges, to.fieldRanges),
        addedFrom = part { addedFrom },
        addedTo = part { addedTo },
        folder = part { folder },
        untaggedOnly = part { untaggedOnly },
        unreviewedOrUntagged = part { unreviewedOrUntagged },
        missingFields = part { missingFields },
    )
}

/** [draft] with each key whose value changed from [from] to [to] taking its value in [to]. */
private fun <V : Any> rebasedByKey(
    draft: Map<String, V>,
    from: Map<String, V>,
    to: Map<String, V>,
): Map<String, V> =
    (from.keys + to.keys)
        .filter { from[it] != to[it] }
        .fold(draft) { edited, key -> to[key]?.let { edited + (key to it) } ?: (edited - key) }

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
