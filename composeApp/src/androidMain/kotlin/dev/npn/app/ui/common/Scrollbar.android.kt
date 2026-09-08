package dev.npn.app.ui.common

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Android draws no permanent scrollbar, so scrollable content reserves no gutter for one. */
actual val ScrollbarWidth: Dp = 0.dp

@Composable
actual fun BoxScope.VerticalScrollbar(
    state: LazyListState,
    modifier: Modifier,
) = Unit

@Composable
actual fun BoxScope.VerticalScrollbar(
    state: LazyGridState,
    modifier: Modifier,
) = Unit
