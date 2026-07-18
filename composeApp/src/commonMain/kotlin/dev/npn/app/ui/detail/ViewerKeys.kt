package dev.npn.app.ui.detail

import androidx.compose.foundation.pager.PagerState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type

/**
 * Keyboard control for the viewer (desktop): arrows / PageUp-PageDown page, Home/End jump to
 * first/last, and +/-/0 zoom in/out/reset. Paging is delegated to [onPage] (which animates the
 * pager); zoom mutates [transform] via [onTransform], anchored at the container center.
 *
 * The host must hold focus for these to fire; [dev.npn.app.ui.detail.DocDetailScreen] gives the
 * viewer a [androidx.compose.ui.focus.FocusRequester] and requests focus on entry.
 */
fun Modifier.viewerKeys(
    pagerState: PagerState,
    pageCount: Int,
    transform: () -> ViewerTransform,
    onTransform: (ViewerTransform) -> Unit,
    containerSize: () -> Size,
    fittedSize: () -> Size,
    onPage: (Int) -> Unit,
): Modifier = onKeyEvent { event ->
    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
    val page = pagerState.currentPage
    fun zoom(delta: Float) = onTransform(zoomAtCenter(transform(), delta, containerSize(), fittedSize()))
    when (event.key) {
        Key.DirectionLeft, Key.PageUp -> { onPage(page - 1); true }
        Key.DirectionRight, Key.PageDown -> { onPage(page + 1); true }
        Key.MoveHome -> { onPage(0); true }
        Key.MoveEnd -> { onPage(pageCount - 1); true }
        Key.Plus, Key.Equals -> { zoom(ZOOM_KEY_STEP); true }
        Key.Minus -> { zoom(-ZOOM_KEY_STEP); true }
        Key.Zero -> { onTransform(ViewerTransform()); true }
        else -> false
    }
}

private const val ZOOM_KEY_STEP = 0.5f

private fun zoomAtCenter(t: ViewerTransform, delta: Float, container: Size, fitted: Size): ViewerTransform {
    if (container == Size.Zero) return t
    val center = Offset(container.width / 2f, container.height / 2f)
    val target = (t.zoom + delta).coerceIn(MIN_ZOOM, MAX_ZOOM)
    return t.applyGesture(
        zoomChange = target / t.zoom,
        pan = Offset.Zero,
        centroid = center,
        fittedSize = fitted,
        container = container,
    )
}
