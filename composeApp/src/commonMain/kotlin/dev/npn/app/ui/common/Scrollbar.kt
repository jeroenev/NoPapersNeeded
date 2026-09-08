package dev.npn.app.ui.common

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

/**
 * Horizontal space a [VerticalScrollbar] occupies. Scrollable content reserves this much at its
 * end edge so nothing ends up underneath the scrollbar; it is zero on platforms that draw none,
 * where the reservation therefore costs nothing.
 */
expect val ScrollbarWidth: Dp

/**
 * Vertical scrollbar for a lazy list, pinned to the end edge of the enclosing box.
 *
 * Desktop draws the platform scrollbar (draggable, click-to-page, always visible); touch platforms
 * draw nothing, since lists there are scrolled by gesture and a permanent scrollbar is not the
 * convention.
 */
@Composable
expect fun BoxScope.VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
)

/** Vertical scrollbar for a lazy grid. See the [LazyListState] overload for the semantics. */
@Composable
expect fun BoxScope.VerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier = Modifier,
)
