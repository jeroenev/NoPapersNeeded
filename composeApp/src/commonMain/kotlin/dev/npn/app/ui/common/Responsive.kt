package dev.npn.app.ui.common

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window width above which the app lays out in its wide form. Strictly above: a window exactly this
 * wide is still compact.
 */
val WideLayoutBreakpoint: Dp = 840.dp

/**
 * Whether the app lays out for a wide window (navigation rail, side panels, split panes) or a
 * compact one (bottom bar, one pane at a time).
 *
 * Decided once, at the root, from the window's width ([ProvideWindowWidthClass]), and read through
 * [LocalWindowWidthClass] by the shell and every screen, so they all switch at the same width. A
 * screen never measures its own box for this: inside the rail that box is narrower than the window,
 * so the screen would still be compact while the shell is already wide.
 */
enum class WindowWidthClass {
    COMPACT,
    WIDE,
    ;

    val isWide: Boolean get() = this == WIDE

    companion object {
        fun of(windowWidth: Dp): WindowWidthClass = if (windowWidth > WideLayoutBreakpoint) WIDE else COMPACT
    }
}

/** The window's [WindowWidthClass]; compact until [ProvideWindowWidthClass] provides it. */
val LocalWindowWidthClass = compositionLocalOf { WindowWidthClass.COMPACT }

/** Provide [LocalWindowWidthClass] for [content] from the size of the window it is shown in. */
@Composable
fun ProvideWindowWidthClass(content: @Composable () -> Unit) {
    val width = LocalWindowInfo.current.containerDpSize.width
    CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthClass.of(width), content = content)
}

/**
 * True when the available area is wider than it is tall. Derived from BoxWithConstraints rather
 * than platform orientation APIs so it works cross-platform (desktop windows have no orientation)
 * and reflects the actual space given to the content, not just the device.
 */
val BoxWithConstraintsScope.isLandscape: Boolean
    get() = maxWidth > maxHeight
