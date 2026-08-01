package dev.npn.app.ui.detail

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * Zoom + pan state for a single page, expressed in the container's coordinate space. [offset] is the
 * translation applied to the page *after* it has been fit-scaled into the container and scaled by
 * [zoom]; the origin is the top-left of the container (matching `transformOrigin = TopLeft` in the
 * viewer's graphicsLayer, which makes the math below exact).
 *
 * All operations are pure so the gesture-critical bits (centroid anchoring, pan clamping) are unit
 * testable in plain JVM tests without a Compose runtime.
 */
data class ViewerTransform(
    val zoom: Float = 1f,
    val offset: Offset = Offset.Zero,
) {
    val isZoomed: Boolean get() = zoom > MIN_ZOOM + ZOOM_EPSILON
}

const val MIN_ZOOM = 1f
const val MAX_ZOOM = 5f
private const val ZOOM_EPSILON = 0.001f

/** Fixed zoom increment for the +/- buttons and keyboard +/- keys (no cursor position). */
const val ZOOM_STEP = 0.5f

/**
 * Zoom-around-center step used when no gesture focus is available (the +/- buttons and the
 * keyboard +/- keys): adds [delta] to the current zoom, clamps to [[MIN_ZOOM], [MAX_ZOOM]], and
 * anchors the change at the container center. No-ops before layout is known ([container] is
 * [Size.Zero]).
 */
fun zoomAtCenter(
    t: ViewerTransform,
    delta: Float,
    container: Size,
    fitted: Size,
): ViewerTransform {
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

/**
 * The size the page bitmap occupies inside [container] at zoom 1, using ContentScale.Fit
 * (letterboxed): scaled uniformly to touch the tighter axis. Returns [Size.Zero] if either
 * dimension is non-positive so callers can no-op before layout is known.
 */
fun fittedContentSize(
    content: Size,
    container: Size,
): Size {
    if (content.minDimension <= 0f || container.minDimension <= 0f) return Size.Zero
    val scale = minOf(container.width / content.width, container.height / content.height)
    return Size(content.width * scale, content.height * scale)
}

/**
 * Clamp [offset] so the scaled page can never leave a gap inside [container]: when the page is
 * larger than the container on an axis it may pan until its edge meets the container edge (no
 * further); when it is smaller (or equal) it is pinned centered on that axis. [fittedSize] is the
 * zoom-1 fit size (see [fittedContentSize]); the on-screen size is `fittedSize * zoom`.
 */
fun clampPan(
    offset: Offset,
    fittedSize: Size,
    container: Size,
    zoom: Float,
): Offset {
    fun clampAxis(
        value: Float,
        scaledContent: Float,
        containerExtent: Float,
    ): Float {
        // Center the content, then allow travel of half the overflow in each direction.
        val overflow = scaledContent - containerExtent
        if (overflow <= 0f) return 0f // smaller than container: pin centered.
        val limit = overflow / 2f
        return value.coerceIn(-limit, limit)
    }
    return Offset(
        x = clampAxis(offset.x, fittedSize.width * zoom, container.width),
        y = clampAxis(offset.y, fittedSize.height * zoom, container.height),
    )
}

/**
 * Apply a zoom change anchored at [centroid] (a point in container coordinates) plus a [pan], then
 * clamp the resulting pan to keep the page inside [container]. This is the standard "zoom toward the
 * gesture focus" update: the container point under the centroid stays put as the scale changes.
 *
 * With `transformOrigin = TopLeft`, a point p in container space maps from content space c as
 * `p = c*zoom + offset + center`, where `center` recenters the fitted page. Holding the content
 * point under [centroid] fixed across the zoom change gives the offset update below.
 */
fun ViewerTransform.applyGesture(
    zoomChange: Float,
    pan: Offset,
    centroid: Offset,
    fittedSize: Size,
    container: Size,
): ViewerTransform {
    val newZoom = (zoom * zoomChange).coerceIn(MIN_ZOOM, MAX_ZOOM)
    // The page is centered in the container at rest; the centroid is measured from that center.
    val center = Offset(container.width / 2f, container.height / 2f)
    val focus = centroid - center
    // Keep the content point under the focus fixed: newOffset = focus - (focus - oldOffset) * ratio.
    val ratio = newZoom / zoom
    val newOffset =
        if (newZoom <= MIN_ZOOM + ZOOM_EPSILON) {
            Offset.Zero // fully zoomed out: always recenter.
        } else {
            focus - (focus - offset) * ratio + pan
        }
    return ViewerTransform(
        zoom = newZoom,
        offset = clampPan(newOffset, fittedSize, container, newZoom),
    )
}

/**
 * Toggle between fit (1x) and [zoomedLevel], anchoring the zoom-in at [tap] (container coords).
 * Used by double-tap. Zooming out always returns to a centered fit.
 */
fun ViewerTransform.toggleZoom(
    tap: Offset,
    fittedSize: Size,
    container: Size,
    zoomedLevel: Float = 2.5f,
): ViewerTransform =
    if (isZoomed) {
        ViewerTransform()
    } else {
        applyGesture(
            zoomChange = zoomedLevel / zoom,
            pan = Offset.Zero,
            centroid = tap,
            fittedSize = fittedSize,
            container = container,
        )
    }

/**
 * Which page-swipe directions the pager may claim while zoomed in, given the current pan against its
 * clamp limits on the horizontal axis. When zoomed, the transform gesture owns horizontal drag until
 * the page has been panned to its left/right edge; only then may the pager take over in that
 * direction. At 1x the pager is always free.
 */
fun ViewerTransform.canPagerConsumeHorizontal(
    fittedSize: Size,
    container: Size,
): Boolean {
    if (!isZoomed) return true
    val overflow = fittedSize.width * zoom - container.width
    if (overflow <= 0f) return true // page not wider than container: nothing to pan into.
    val limit = overflow / 2f
    // At an edge (within epsilon) the pan can go no further, so hand off to the pager.
    return offset.x <= -limit + 0.5f || offset.x >= limit - 0.5f
}
