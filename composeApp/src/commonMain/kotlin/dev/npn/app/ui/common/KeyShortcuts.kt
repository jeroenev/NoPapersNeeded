package dev.npn.app.ui.common

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type

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

private fun KeyEvent.runShortcut(action: (KeyEvent) -> (() -> Unit)?): Boolean {
    if (type != KeyEventType.KeyDown) return false
    val run = action(this) ?: return false
    run()
    return true
}
