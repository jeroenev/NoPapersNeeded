package dev.npn.app

import dev.npn.app.ui.library.LibraryKey
import dev.npn.app.ui.library.LibraryKeyPress
import dev.npn.app.ui.library.LibraryShortcut
import dev.npn.app.ui.library.libraryShortcutFor
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pure tests for the library keymap decision. No Compose, no focus, no viewmodel — a key press plus
 * selection state in, an intent out. These own the shortcut semantics the composable adapter used
 * to bury behind focus requesters and callbacks.
 */
class LibraryShortcutsTest {
    private fun shortcut(
        key: LibraryKey,
        ctrl: Boolean = false,
        selectionMode: Boolean = false,
    ): LibraryShortcut? = libraryShortcutFor(LibraryKeyPress(key, ctrl), selectionMode)

    @Test
    fun ctrlFAndSlashBothFocusSearch() {
        shortcut(LibraryKey.F, ctrl = true) shouldBe LibraryShortcut.FOCUS_SEARCH
        shortcut(LibraryKey.SLASH) shouldBe LibraryShortcut.FOCUS_SEARCH
    }

    @Test
    fun ctrlOImports() {
        shortcut(LibraryKey.O, ctrl = true) shouldBe LibraryShortcut.IMPORT
    }

    @Test
    fun ctrlASelectsAll() {
        shortcut(LibraryKey.A, ctrl = true) shouldBe LibraryShortcut.SELECT_ALL
    }

    @Test
    fun ctrlBoundKeysDoNothingWithoutCtrl() {
        // F/O/A only bind with Ctrl; the bare key falls through so plain typing is never swallowed.
        shortcut(LibraryKey.F) shouldBe null
        shortcut(LibraryKey.O) shouldBe null
        shortcut(LibraryKey.A) shouldBe null
    }

    @Test
    fun deleteRequestsTrashOnlyInSelectionMode() {
        shortcut(LibraryKey.DELETE, selectionMode = true) shouldBe LibraryShortcut.REQUEST_TRASH
        // Outside selection there is nothing to trash: Delete is not bound.
        shortcut(LibraryKey.DELETE, selectionMode = false) shouldBe null
    }

    @Test
    fun escapeClearsSelectionWhenInSelectionMode() {
        shortcut(LibraryKey.ESCAPE, selectionMode = true) shouldBe LibraryShortcut.CLEAR_SELECTION
    }

    @Test
    fun escapeClearsQueryWhenNotInSelectionMode() {
        shortcut(LibraryKey.ESCAPE, selectionMode = false) shouldBe LibraryShortcut.CLEAR_QUERY
    }

    @Test
    fun slashIsNotAffectedBySelectionModeOrCtrl() {
        shortcut(LibraryKey.SLASH, selectionMode = true) shouldBe LibraryShortcut.FOCUS_SEARCH
        shortcut(LibraryKey.SLASH, ctrl = true) shouldBe LibraryShortcut.FOCUS_SEARCH
    }
}
