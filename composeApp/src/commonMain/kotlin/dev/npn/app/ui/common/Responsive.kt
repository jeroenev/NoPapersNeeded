package dev.npn.app.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Picks between [wideContent] and [narrowContent] based on available width. We deliberately
 * avoid the material3-window-size-class artifact (not on the classpath) and derive the
 * breakpoint from BoxWithConstraints instead: >840dp is treated as expanded.
 */
@Composable
fun BoxWithConstraintsLayout(
    wideContent: @Composable () -> Unit,
    narrowContent: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > 840.dp) wideContent() else narrowContent()
    }
}

/**
 * True when the available area is wider than it is tall. Derived from BoxWithConstraints rather
 * than platform orientation APIs so it works cross-platform (desktop windows have no orientation)
 * and reflects the actual space given to the content, not just the device.
 */
val BoxWithConstraintsScope.isLandscape: Boolean
    get() = maxWidth > maxHeight
