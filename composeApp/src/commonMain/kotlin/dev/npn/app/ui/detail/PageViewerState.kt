package dev.npn.app.ui.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import kotlin.math.abs

/**
 * The [PageViewer]'s gesture state: each page's zoom and pan, and the scroll gathered toward the next
 * wheel page flip. Snapshot state behind one stable object, so the gesture handlers, which
 * `pointerInput` captures once and never refreshes, always read and write the current values.
 */
@Stable
internal class PageViewerState {
    // Per page, so zooming one page and swiping away leaves the next one at rest.
    private val transforms = mutableStateMapOf<Int, ViewerTransform>()
    private val wheelScroll = mutableFloatStateOf(0f)

    fun transformOf(page: Int): ViewerTransform = transforms[page] ?: ViewerTransform()

    fun setTransform(
        page: Int,
        transform: ViewerTransform,
    ) {
        transforms[page] = transform
    }

    /**
     * Gather a plain-wheel [delta] made at 1x toward a page flip ([wheelPageStep]); returns the pages
     * to move by, -1, 0 or 1.
     */
    fun wheelPages(delta: Float): Int {
        val step = wheelPageStep(wheelScroll.floatValue, delta)
        wheelScroll.floatValue = step.remainder
        return step.pages
    }
}

/** What one wheel event does to the paging: [pages] to move by (-1, 0 or 1), and the scroll still gathered. */
internal data class WheelStep(
    val pages: Int,
    val remainder: Float,
)

/**
 * One wheel event of [delta] onto the scroll [gathered] so far toward a page flip. A mouse wheel notch
 * arrives as a single event of at least 1 and flips at once; a touchpad's fractional scroll adds up
 * until it does. Scrolling back the other way starts over from that event, so a hesitation never
 * flips the wrong way.
 */
internal fun wheelPageStep(
    gathered: Float,
    delta: Float,
): WheelStep {
    val sameDirection = gathered == 0f || (gathered > 0f) == (delta > 0f)
    val total = if (sameDirection) gathered + delta else delta
    return when {
        abs(total) < 1f -> WheelStep(pages = 0, remainder = total)
        total > 0f -> WheelStep(pages = 1, remainder = 0f)
        else -> WheelStep(pages = -1, remainder = 0f)
    }
}
