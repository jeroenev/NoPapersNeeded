package dev.npn.app.ui.library

import dev.npn.core.app.LibraryFilter

/** One-shot navigation handoff for applying a filter to the existing library destination. */
class LibraryFilterHandoff {
    private var pending: LibraryFilter? = null

    fun set(filter: LibraryFilter) {
        pending = filter
    }

    fun consume(): LibraryFilter? = pending.also { pending = null }
}
