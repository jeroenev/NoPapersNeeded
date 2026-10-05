package dev.npn.app.ui.library

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import dev.npn.app.ui.common.keyShortcuts

/**
 * The library's desktop shortcuts: Ctrl+F or / focuses the search, Ctrl+O imports and Ctrl+A
 * selects every document shown. In [selectionMode], Delete asks to trash the selection and Escape
 * leaves selection mode; outside it Delete is not bound and Escape clears the search, so Escape
 * backs out one level at a time.
 *
 * All of them stay off while [searchFocused], so typing and the search field's own keys (Escape
 * closing its suggestions) are never taken.
 */
internal fun Modifier.libraryKeys(
    searchFocused: Boolean,
    selectionMode: Boolean,
    onFocusSearch: () -> Unit,
    onImport: () -> Unit,
    onSelectAll: () -> Unit,
    onRequestTrash: () -> Unit,
    onClearSelection: () -> Unit,
    onClearQuery: () -> Unit,
): Modifier =
    keyShortcuts { event ->
        val ctrl = event.isCtrlPressed
        when (event.key) {
            Key.Slash -> onFocusSearch
            Key.F -> onFocusSearch.takeIf { ctrl }
            Key.O -> onImport.takeIf { ctrl }
            Key.A -> onSelectAll.takeIf { ctrl }
            Key.Delete -> onRequestTrash.takeIf { selectionMode }
            Key.Escape -> if (selectionMode) onClearSelection else onClearQuery
            else -> null
        }.takeUnless { searchFocused }
    }
