package dev.npn.core.app

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Contract between the service layer (implemented in dev.npn.core.app) and the UI layer
// (composeApp). UI code must depend only on these types plus the db entities they carry.

/** An inclusive bound pair on one field, in the field type's canonical string form (either end open). */
@Serializable
data class FieldRange(
    val from: String? = null,
    val to: String? = null,
) {
    val isEmpty: Boolean get() = from == null && to == null
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

enum class ViewType { LIST, GRID }

@Serializable
data class SavedView(
    val id: String,
    val name: String,
    val filter: LibraryFilter = LibraryFilter(),
    val sort: LibrarySort = LibrarySort(),
    val viewType: ViewType = ViewType.LIST,
    val pinned: Boolean = false,
)
