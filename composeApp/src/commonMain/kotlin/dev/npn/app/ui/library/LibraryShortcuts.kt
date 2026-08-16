package dev.npn.app.ui.library

/**
 * A key press reduced to just what the library shortcut map cares about, free of any Compose type.
 *
 * [LibraryScreen] translates a Compose `KeyEvent` into this on the desktop (a key-down whose key it
 * recognises, plus whether Ctrl was held) so the decision below can be unit-tested without a
 * Compose harness. Keys the map never uses are intentionally absent from [LibraryKey].
 */
data class LibraryKeyPress(
    val key: LibraryKey,
    val ctrl: Boolean,
)

/** The keys the library screen binds. Everything else falls through to the platform. */
enum class LibraryKey { F, O, A, SLASH, DELETE, ESCAPE }

/**
 * What a recognised shortcut asks the screen to do. Kept as intents rather than callbacks so the
 * mapping is a pure value the tests can assert; [LibraryScreen] dispatches them to the real
 * viewmodel/focus actions.
 */
enum class LibraryShortcut {
    /** Ctrl+F or "/": move focus into the search field. */
    FOCUS_SEARCH,

    /** Ctrl+O: open the import picker. */
    IMPORT,

    /** Ctrl+A: select every visible document (enters selection mode). */
    SELECT_ALL,

    /** Delete, only while in selection mode: request trashing the selection. */
    REQUEST_TRASH,

    /** Escape while in selection mode: leave selection mode. */
    CLEAR_SELECTION,

    /** Escape while NOT in selection mode: clear the search query. */
    CLEAR_QUERY,
}

/**
 * Map a key press to the library shortcut it triggers, or null when nothing is bound.
 *
 * Pure and total. The two branches worth pinning down in tests:
 *  - [LibraryKey.DELETE] only fires (as [LibraryShortcut.REQUEST_TRASH]) while [selectionMode] is
 *    on; outside selection there is nothing to trash, so it falls through.
 *  - [LibraryKey.ESCAPE] is context-sensitive: in selection mode it clears the selection, otherwise
 *    it clears the search query. This is the load-bearing "Escape backs out one level at a time"
 *    rule — you leave selection first, and only a second Escape clears the query.
 *
 * The caller is responsible for the "search field holds focus" gate: it must not build a
 * [LibraryKeyPress] at all while the search field is focused, so typing (and the field's own
 * Escape) is never swallowed here.
 */
fun libraryShortcutFor(
    press: LibraryKeyPress,
    selectionMode: Boolean,
): LibraryShortcut? =
    when {
        press.ctrl && press.key == LibraryKey.F -> {
            LibraryShortcut.FOCUS_SEARCH
        }

        press.ctrl && press.key == LibraryKey.O -> {
            LibraryShortcut.IMPORT
        }

        press.ctrl && press.key == LibraryKey.A -> {
            LibraryShortcut.SELECT_ALL
        }

        press.key == LibraryKey.SLASH -> {
            LibraryShortcut.FOCUS_SEARCH
        }

        press.key == LibraryKey.DELETE && selectionMode -> {
            LibraryShortcut.REQUEST_TRASH
        }

        press.key == LibraryKey.ESCAPE -> {
            if (selectionMode) LibraryShortcut.CLEAR_SELECTION else LibraryShortcut.CLEAR_QUERY
        }

        else -> {
            null
        }
    }
