package dev.npn.app.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Width above which layouts switch to their expanded (side-by-side) form. Shared by every
 * adaptive layout in the app so the navigation shell, library panes, and detail panes all
 * flip at the same window width.
 */
val WideLayoutBreakpoint: Dp = 840.dp

/**
 * Width below which the selection top bar collapses its least frequent actions (the review
 * marks) into an overflow menu. The tag/trash actions stay as direct icons at every width.
 * Deliberately far below [WideLayoutBreakpoint]: a landscape phone is too narrow for the rail
 * yet easily fits every icon, so the actions only overflow once the window is genuinely
 * phone-portrait tight.
 */
val CompactTopBarBreakpoint: Dp = 480.dp

/**
 * Picks between [wideContent] and [narrowContent] based on available width. We deliberately
 * avoid the material3-window-size-class artifact (not on the classpath) and derive the
 * breakpoint from BoxWithConstraints instead: >[WideLayoutBreakpoint] is treated as expanded.
 */
@Composable
fun BoxWithConstraintsLayout(
    wideContent: @Composable () -> Unit,
    narrowContent: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > WideLayoutBreakpoint) wideContent() else narrowContent()
    }
}

/**
 * True when the available area is wider than it is tall. Derived from BoxWithConstraints rather
 * than platform orientation APIs so it works cross-platform (desktop windows have no orientation)
 * and reflects the actual space given to the content, not just the device.
 */
val BoxWithConstraintsScope.isLandscape: Boolean
    get() = maxWidth > maxHeight
