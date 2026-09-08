package dev.npn.app.ui.common

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.VerticalScrollbar as FoundationVerticalScrollbar

/** The default scrollbar thickness (8.dp) plus a little breathing room next to the content. */
actual val ScrollbarWidth: Dp = 12.dp

@Composable
actual fun BoxScope.VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier,
) {
    FoundationVerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    )
}

@Composable
actual fun BoxScope.VerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier,
) {
    FoundationVerticalScrollbar(
        adapter = rememberScrollbarAdapter(state),
        modifier = modifier.align(Alignment.CenterEnd).fillMaxHeight(),
    )
}
