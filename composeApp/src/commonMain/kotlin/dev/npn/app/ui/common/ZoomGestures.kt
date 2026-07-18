package dev.npn.app.ui.common

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged

/**
 * Multi-touch pinch detection that leaves single-finger gestures alone, so it can wrap scrollable
 * content (e.g. a grid) without stealing its scroll. Events are observed and consumed on the
 * [PointerEventPass.Initial] pass, which runs before the child's scroll logic: while two or more
 * pointers are down the events are consumed here and the child never scrolls; a single-finger drag
 * is never consumed and scrolls normally.
 *
 * [onZoom] receives the incremental zoom factor (>1 = fingers moving apart).
 */
fun Modifier.pinchToZoom(
    onZoom: (Float) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val pressed = event.changes.count { it.pressed }
            if (pressed == 0) break
            if (pressed >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) onZoom(zoom)
                event.changes.forEach { it.consume() }
            }
        }
    }
}

/**
 * Ctrl + mouse-wheel/touchpad scroll, the desktop zoom convention. Plain scrolling passes through
 * untouched; with Ctrl held the scroll event is consumed (on [PointerEventPass.Initial], before
 * the child scrolls) and [onScroll] receives the vertical scroll delta (positive = scroll down) and
 * the cursor position in this element's local coordinates (for cursor-anchored zoom; the grid can
 * ignore it). Touchpads emit fine-grained fractional deltas, so callers should scale rather than
 * step.
 */
fun Modifier.ctrlScrollZoom(
    onScroll: (delta: Float, position: Offset) -> Unit,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                var delta = 0f
                event.changes.forEach { delta += it.scrollDelta.y }
                if (delta != 0f) {
                    val position = event.changes.firstOrNull()?.position ?: Offset.Zero
                    onScroll(delta, position)
                    event.changes.forEach { it.consume() }
                }
            }
        }
    }
}

/**
 * Desktop mouse-wheel / touchpad handling for the page viewer, on the [PointerEventPass.Initial]
 * pass so it runs before the pager's own scroll logic:
 *
 * - **Ctrl held** -> [onZoom] with the scroll delta and cursor position (cursor-anchored zoom).
 * - **No Ctrl** -> [onScroll] with the delta and cursor position; the callback decides what to do
 *   (page at 1x, pan when zoomed) and returns true if it handled the event, in which case the
 *   event is consumed so the pager does not also scroll. Returning false lets the pager handle it.
 *
 * Keeping both in one handler guarantees a single consume decision per event (two separate
 * pointerInput modifiers would race for the same scroll event).
 */
fun Modifier.viewerScroll(
    onZoom: (delta: Float, position: Offset) -> Unit,
    onScroll: (delta: Float, position: Offset) -> Boolean,
): Modifier = pointerInput(Unit) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val delta = if (event.type == PointerEventType.Scroll) {
                event.changes.sumOf { it.scrollDelta.y.toDouble() }.toFloat()
            } else {
                0f
            }
            if (delta != 0f) {
                val position = event.changes.firstOrNull()?.position ?: Offset.Zero
                val consumed = if (event.keyboardModifiers.isCtrlPressed) {
                    onZoom(delta, position)
                    true
                } else {
                    onScroll(delta, position)
                }
                if (consumed) event.changes.forEach { it.consume() }
            }
        }
    }
}

/**
 * Detects two-or-more-finger pinch only, reporting the incremental [centroid] and zoom factor for
 * each move. Single-pointer drags are ignored (and not consumed) so a parent pager can swipe. For
 * use inside a `pointerInput { }` block.
 */
suspend fun PointerInputScope.detectPinchTransform(
    onPinch: (centroid: Offset, zoomChange: Float) -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        while (true) {
            val event = awaitPointerEvent()
            if (event.changes.none { it.pressed }) break
            if (event.changes.count { it.pressed } >= 2) {
                val zoom = event.calculateZoom()
                if (zoom != 1f) {
                    onPinch(event.calculateCentroid(useCurrent = true), zoom)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        }
    }
}

/**
 * Single-pointer drag that only engages when [isZoomed] returns true at the moment the drag starts
 * moving. When not zoomed the pointer is left unconsumed so a parent pager receives the swipe; this
 * is what makes mouse-drag paging work at 1x while drag pans when zoomed. For use inside a
 * `pointerInput { }` block. [onDrag] receives the incremental pan.
 */
suspend fun PointerInputScope.detectDragWhenZoomed(
    isZoomed: () -> Boolean,
    onDrag: (pan: Offset) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (!isZoomed()) return@awaitEachGesture // let the pager have this drag.
        drag(down.id) { change ->
            onDrag(change.positionChange())
            change.consume()
        }
    }
}
