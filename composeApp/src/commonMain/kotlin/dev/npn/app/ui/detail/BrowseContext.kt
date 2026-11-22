package dev.npn.app.ui.detail

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * The ordered list of documents the user is browsing, so the detail screen can walk to the
 * prev/next document without round-tripping through the library. Populated with the library's
 * current sorted+filtered doc ids when a document is opened; ids are stable so the list stays valid
 * across the navigation. Also carries the originating search query so the detail screen can
 * highlight matching terms in the OCR text tab.
 *
 * Navigation state, not library data: it lives with the open library's screens
 * ([dev.npn.app.LibraryHandover], in the session's viewmodel store), so a recreated window keeps
 * it, and nothing outside the UI knows it.
 */
class BrowseContext {
    private var docIds: List<String> by mutableStateOf(emptyList())

    /** The ordered documents the user is browsing; empty until the library hands an order over. */
    val order: List<String> get() = docIds

    /** Search query that produced the current browse order, if any. */
    var searchQuery: String? by mutableStateOf(null)
        private set

    fun setOrder(
        ids: List<String>,
        query: String? = null,
    ) {
        docIds = ids
        searchQuery = query
    }

    fun neighbour(
        docId: String,
        delta: Int,
    ): String? {
        val i = docIds.indexOf(docId)
        if (i < 0) return null
        return docIds.getOrNull(i + delta)
    }

    /** 1-based position and total for a "3 of 41" indicator, or null if the doc is not in the list. */
    fun positionOf(docId: String): Pair<Int, Int>? {
        val i = docIds.indexOf(docId)
        if (i < 0) return null
        return (i + 1) to docIds.size
    }
}
