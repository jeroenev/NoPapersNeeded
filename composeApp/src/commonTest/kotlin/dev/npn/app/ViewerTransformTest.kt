package dev.npn.app

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import dev.npn.app.ui.detail.MAX_ZOOM
import dev.npn.app.ui.detail.MIN_ZOOM
import dev.npn.app.ui.detail.ViewerTransform
import dev.npn.app.ui.detail.applyGesture
import dev.npn.app.ui.detail.canPagerConsumeHorizontal
import dev.npn.app.ui.detail.clampPan
import dev.npn.app.ui.detail.fittedContentSize
import dev.npn.app.ui.detail.toggleZoom
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ViewerTransformTest {

    private val container = Size(1000f, 800f)

    @Test
    fun fitLetterboxesToTighterAxis() {
        // A wide page in a less-wide container fits by width.
        val fitted = fittedContentSize(content = Size(2000f, 1000f), container = container)
        fitted.width shouldBe 1000f
        fitted.height shouldBe 500f
    }

    @Test
    fun fitReturnsZeroForDegenerateInput() {
        fittedContentSize(Size(0f, 100f), container) shouldBe Size.Zero
        fittedContentSize(Size(100f, 100f), Size.Zero) shouldBe Size.Zero
    }

    @Test
    fun panPinnedCenteredWhenContentSmallerThanContainer() {
        val fitted = Size(600f, 400f)
        clampPan(Offset(200f, 200f), fitted, container, zoom = 1f) shouldBe Offset.Zero
    }

    @Test
    fun panClampsToHalfOverflowWhenZoomed() {
        val fitted = Size(1000f, 800f) // exactly fills at 1x
        // At 2x, overflow is 1000 wide / 800 tall -> limits 500 / 400.
        val clamped = clampPan(Offset(9999f, -9999f), fitted, container, zoom = 2f)
        clamped.x shouldBe 500f
        clamped.y shouldBe -400f
    }

    @Test
    fun zoomTowardCornerKeepsThatPointFixed() {
        val fitted = Size(1000f, 800f)
        val corner = Offset(1000f, 800f) // bottom-right corner of the container
        val t = ViewerTransform().applyGesture(
            zoomChange = 2f,
            pan = Offset.Zero,
            centroid = corner,
            fittedSize = fitted,
            container = container,
        )
        t.zoom shouldBe 2f
        // The bottom-right must remain pinned: at 2x that means panning content up-left by the
        // full overflow limit on each axis.
        t.offset.x shouldBe (-500f plusOrMinus 0.5f)
        t.offset.y shouldBe (-400f plusOrMinus 0.5f)
    }

    @Test
    fun zoomClampsToRange() {
        val fitted = Size(1000f, 800f)
        val center = Offset(500f, 400f)
        val maxed = ViewerTransform(zoom = 4f).applyGesture(10f, Offset.Zero, center, fitted, container)
        maxed.zoom shouldBe MAX_ZOOM
        val minned = ViewerTransform(zoom = 1.2f).applyGesture(0.1f, Offset.Zero, center, fitted, container)
        minned.zoom shouldBe MIN_ZOOM
        minned.offset shouldBe Offset.Zero // recenters when fully out
    }

    @Test
    fun doubleTapTogglesFitAndBack() {
        val fitted = Size(1000f, 800f)
        val tapped = ViewerTransform().toggleZoom(Offset(500f, 400f), fitted, container)
        tapped.zoom shouldBe 2.5f
        val back = tapped.toggleZoom(Offset(500f, 400f), fitted, container)
        back shouldBe ViewerTransform()
    }

    @Test
    fun pagerFreeAtRestZoom() {
        ViewerTransform().canPagerConsumeHorizontal(Size(1000f, 800f), container).shouldBeTrue()
    }

    @Test
    fun pagerBlockedMidPanButFreeAtEdges() {
        val fitted = Size(1000f, 800f)
        // Zoomed 2x, centered: mid-pan, pager must not steal the swipe.
        ViewerTransform(zoom = 2f, offset = Offset.Zero)
            .canPagerConsumeHorizontal(fitted, container).shouldBeFalse()
        // Panned to the right edge (offset x at +limit): pager may take over.
        ViewerTransform(zoom = 2f, offset = Offset(500f, 0f))
            .canPagerConsumeHorizontal(fitted, container).shouldBeTrue()
    }
}
