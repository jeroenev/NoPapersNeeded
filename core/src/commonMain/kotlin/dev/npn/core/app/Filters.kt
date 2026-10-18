package dev.npn.core.app

import dev.npn.core.model.ReviewMode
import dev.npn.core.util.KnownEnumSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Contract between the service layer (implemented in dev.npn.core.app) and the UI layer
// (composeApp). None of these types carries a db entity: the index's rows never leave core (see Doc).

/** An inclusive bound pair on one field, in the field type's canonical string form (either end open). */
@Serializable
data class FieldRange(
    val from: String? = null,
    val to: String? = null,
) {
    val isEmpty: Boolean get() = from == null && to == null
}

/**
 * The filter that lists this mode's review queue, as the library's review preset applies it and
 * [DocumentRepository.observeReviewQueueCount] counts it: the untagged documents in
 * [ReviewMode.UNTAGGED_ONLY], and those unreviewed or untagged in [ReviewMode.INBOX_WORKFLOW].
 */
fun ReviewMode.reviewQueueFilter(): LibraryFilter =
    when (this) {
        ReviewMode.UNTAGGED_ONLY -> LibraryFilter(untaggedOnly = true)
        ReviewMode.INBOX_WORKFLOW -> LibraryFilter(unreviewedOrUntagged = true)
    }

@Serializable
data class LibraryFilter(
    /** Restrict results to these document identities, however many; empty means no identity restriction. */
    val docIds: List<String> = emptyList(),
    val query: String? = null, // FTS query; null/blank = no text search
    val tagsAll: List<String> = emptyList(),
    val tagsAny: List<String> = emptyList(),
    val tagsNone: List<String> = emptyList(),
    /** null = any; true/false filters the reviewed marker. */
    val reviewed: Boolean? = null,
    /** Field key -> exact canonical value the document must carry. AND-ed. */
    val fieldEquals: Map<String, String> = emptyMap(),
    /** Field key -> inclusive range on the canonical value (dates lexicographic, numbers numeric). AND-ed. */
    val fieldRanges: Map<String, FieldRange> = emptyMap(),
    val addedFrom: Long? = null, // epoch millis, inclusive
    val addedTo: Long? = null,
    val folder: String? = null, // relPath prefix
    val untaggedOnly: Boolean = false,
    val unreviewedOrUntagged: Boolean = false, // INBOX_WORKFLOW review preset: !reviewed OR no tags
    // Documents where at least ONE of these fields has no value; empty = no such constraint.
    // OR-ed (unlike the AND-ed rest of the filter) so "still needs data" is expressible in one pass.
    val missingFields: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = this == LibraryFilter()

    /** This filter with field [key] required to equal [value]; a null [value] drops the constraint. */
    fun withFieldEquals(
        key: String,
        value: String?,
    ): LibraryFilter = copy(fieldEquals = if (value == null) fieldEquals - key else fieldEquals + (key to value))

    /**
     * This filter with the range on [key] from [from] to [to], a bound not given kept as it is; an
     * empty range drops the constraint.
     */
    fun withFieldBounds(
        key: String,
        from: String? = fieldRanges[key]?.from,
        to: String? = fieldRanges[key]?.to,
    ): LibraryFilter {
        val range = FieldRange(from, to)
        return copy(fieldRanges = if (range.isEmpty) fieldRanges - key else fieldRanges + (key to range))
    }
}

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

/** What the library list is ordered by. Fields sort through `document_fields`; the rest are document columns. */
@Serializable
sealed class SortField {
    @Serializable
    @SerialName("name")
    data object Name : SortField()

    @Serializable
    @SerialName("added")
    data object Added : SortField()

    @Serializable
    @SerialName("size")
    data object Size : SortField()

    /** Sort by one registry field; numeric shadows first when present, else the canonical value. */
    @Serializable
    @SerialName("field")
    data class Field(
        val key: String,
    ) : SortField()

    /** Compact string form for the settings store: `name`, `added`, `size`, `field:<key>`. */
    val wire: String
        get() =
            when (this) {
                Name -> "name"
                Added -> "added"
                Size -> "size"
                is Field -> "field:$key"
            }

    companion object {
        /** Inverse of [wire]; also reads the pre-registry enum names. Null for anything else. */
        fun fromWire(value: String?): SortField? =
            when (value) {
                null -> null
                "name", "NAME" -> Name
                "added", "ADDED" -> Added
                "size", "SIZE" -> Size
                "DOC_DATE" -> Field("docDate")
                else -> value.removePrefix("field:").takeIf { it != value && it.isNotBlank() }?.let(::Field)
            }
    }
}

@Serializable
data class LibrarySort(
    val field: SortField = SortField.Added,
    val ascending: Boolean = false,
)

@Serializable(with = ViewTypeSerializer::class)
enum class ViewType { LIST, GRID }

/**
 * [ViewType] by name. A saved view of a type this build does not know does not decode, so it is not
 * listed here, where a rename or a pin would write it back as the default type.
 */
object ViewTypeSerializer : KnownEnumSerializer<ViewType>("ViewType", ViewType.entries)

@Serializable
data class SavedView(
    val id: String,
    val name: String,
    val filter: LibraryFilter = LibraryFilter(),
    val sort: LibrarySort = LibrarySort(),
    val viewType: ViewType = ViewType.LIST,
    val pinned: Boolean = false,
)
