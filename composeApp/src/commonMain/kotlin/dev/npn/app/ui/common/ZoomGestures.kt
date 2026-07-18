package dev.npn.app.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Multi-touch pinch detection that leaves single-finger gestures alone, so it can wrap scrollable
 * content (e.g. a grid) without stealing its scroll. Events are observed and consumed on the
 * [PointerEventPass.Initial] pass, which runs before the child's scroll logic: while two or more
 * pointers are down the events are consumed here and the child never scrolls; a single-finger drag
 * is never consumed and scrolls normally.
 *
 * [onZoom] receives the incremental zoom factor (>1 = fingers moving apart). [onZoomEnd] fires
 * once when a gesture that actually pinched ends.
 */
fun Modifier.pinchToZoom(
    onZoom: (Float) -> Unit,
    onZoomEnd: () -> Unit = {},
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        var pinched = false
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            if (pressed >= 2) {
                pinched = true
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom(zoom)
                event.changes.forEach { it.consume() }
            }
        }
        if (pinched) onZoomEnd()
    }
}

/**
 * Ctrl + mouse-wheel/touchpad scroll, the desktop zoom convention. Plain scrolling passes through
 * untouched; with Ctrl held the scroll event is consumed (on [PointerEventPass.Initial], before
 * the child scrolls) and [onScroll] receives the vertical scroll delta (positive = scroll down).
 * Touchpads emit fine-grained fractional deltas, so callers should scale rather than step.
 */
fun Modifier.ctrlScrollZoom(
    onScroll: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                var delta = 0f
                event.changes.forEach { delta += it.scrollDelta.y }
                if (delta != 0f) {
                    onScroll(delta)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}
