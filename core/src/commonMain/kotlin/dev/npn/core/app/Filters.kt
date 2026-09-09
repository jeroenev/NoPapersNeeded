package dev.npn.core.app

import kotlinx.serialization.Serializable

// Contract between the service layer (implemented in dev.npn.core.app) and the UI layer
// (composeApp). UI code must depend only on these types plus the db entities they carry.

/** Single-value document fields whose absence can be filtered on; see [LibraryFilter.missingAny]. */
enum class DocField { TITLE, CORRESPONDENT, DOC_DATE }

@Serializable
data class LibraryFilter(
    /** Restrict results to these document identities; empty means no identity restriction. */
    val docIds: List<String> = emptyList(),
    val query: String? = null, // FTS query; null/blank = no text search
    val tagsAll: List<String> = emptyList(),
    val tagsAny: List<String> = emptyList(),
    val tagsNone: List<String> = emptyList(),
    val reviewed: Boolean? = null, // null = any; true/false filters the reviewed marker
    val correspondent: String? = null,
    val docType: String? = null,
    val docDateFrom: String? = null, // ISO yyyy-MM-dd, inclusive
    val docDateTo: String? = null,
    val addedFrom: Long? = null, // epoch millis, inclusive
    val addedTo: Long? = null,
    val folder: String? = null, // relPath prefix
    val untaggedOnly: Boolean = false,
    val unreviewedOrUntagged: Boolean = false, // INBOX_WORKFLOW review preset: !reviewed OR no tags
    // Documents where at least ONE of these fields is null/blank; empty = no such constraint.
    // OR-ed (unlike the AND-ed rest of the filter) so "still needs data" is expressible in one pass.
    val missingAny: List<DocField> = emptyList(),
) {
    val isEmpty: Boolean
        get() = this == LibraryFilter()
}

enum class SortField { NAME, ADDED, DOC_DATE, SIZE }

@Serializable
data class LibrarySort(
    val field: SortField = SortField.ADDED,
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
