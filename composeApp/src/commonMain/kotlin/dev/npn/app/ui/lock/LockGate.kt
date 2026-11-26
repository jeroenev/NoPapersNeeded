package dev.npn.app.ui.lock

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.staticCompositionLocalOf
import dev.npn.core.security.AppLock

/**
 * [content] behind the app lock: while [locked], [lockScreen] takes its place, and what [content]
 * saved (the document swiped to, an open dialog, a scroll position, a sign-in page already opened)
 * is kept for when it comes back. The content is removed rather than covered, so nothing behind the
 * lock is read out, takes focus or answers a shortcut.
 */
@Composable
internal fun LockGate(
    locked: Boolean,
    lockScreen: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    val kept = rememberSaveableStateHolder()
    if (locked) {
        lockScreen()
    } else {
        kept.SaveableStateProvider(CONTENT_KEY, content)
    }
}

private const val CONTENT_KEY = "app-content"

/**
 * The app's lock, for the screens that change it (Settings asks it for the current secret). The app
 * root provides it; a screen rendered without one fails at once rather than checking against a lock
 * that is not the app's.
 */
internal val LocalAppLock = staticCompositionLocalOf<AppLock> { error("The app root provides the app lock") }
