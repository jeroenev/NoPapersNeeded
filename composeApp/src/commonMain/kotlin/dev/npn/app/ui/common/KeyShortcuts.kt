package dev.npn.app.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint

/**
 * Keyboard shortcuts, the one way the app binds keys: [action] names what a key press does, usually
 * with a `when (event.key)`, or returns null for a key it leaves alone, which then passes on to the
 * rest of the screen. Key releases always pass on. The node only sees keys while it or something
 * inside it holds focus.
 */
fun Modifier.keyShortcuts(action: (KeyEvent) -> (() -> Unit)?): Modifier = onKeyEvent { it.runShortcut(action) }

/**
 * [keyShortcuts] that sees each key before the focused element does, for a shortcut that must work
 * while a text field holds focus.
 */
fun Modifier.previewKeyShortcuts(action: (KeyEvent) -> (() -> Unit)?): Modifier =
    onPreviewKeyEvent { it.runShortcut(action) }

/**
 * A shortcut on a typed character rather than a key, such as `?`: [action] gets the character and
 * names what it does, or returns null to leave it alone. A character follows the keyboard layout
 * (`?` is Shift+/ on one layout and Shift+ß on another), and a focused text field takes the
 * characters typed into it first, so typing one there never fires the shortcut. Desktop delivers
 * typed characters as events of their own; Android does not, so there this never fires.
 */
fun Modifier.typedKeyShortcuts(action: (Char) -> (() -> Unit)?): Modifier =
    onKeyEvent { event ->
        if (event.type != KeyEventType.Unknown || event.utf16CodePoint == 0) return@onKeyEvent false
        val run = action(event.utf16CodePoint.toChar()) ?: return@onKeyEvent false
        run()
        true
    }

private fun KeyEvent.runShortcut(action: (KeyEvent) -> (() -> Unit)?): Boolean {
    if (type != KeyEventType.KeyDown) return false
    val run = action(this) ?: return false
    run()
    return true
}
