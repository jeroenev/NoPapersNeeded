@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.click
import dev.npn.app.ui.contentDescription
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.theme.NpnTheme
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.shouldBe
import org.jetbrains.skia.EncodedImageFormat
import kotlin.test.Test

/** [PageViewer] against a real composition, with the page index fed back the way the viewmodel does. */
class PageViewerTest : EnglishLocaleTest() {
    /** Paging forward and back again must leave the viewmodel on the page the viewer shows. */
    @Test
    fun everySettledPageReachesTheViewModel() =
        onUiThread {
            var pageIndex by mutableIntStateOf(0)
            val scene =
                ImageComposeScene(WIDTH, HEIGHT) {
                    NpnTheme(darkTheme = false) {
                        Surface {
                            PageViewer(
                                initialPage = pageIndex,
                                pageCount = PageCountState.Ready(3),
                                render = { _, _ -> null },
                                onPageChange = { pageIndex = it },
                                onRetryPageCount = {},
                            )
                        }
                    }
                }
            try {
                scene.settle()
                scene.click("Next page")
                scene.settle()
                pageIndex shouldBe 1

                scene.click("Previous page")
                scene.settle()
                pageIndex shouldBe 0
            } finally {
                scene.close()
            }
        }

    /**
     * A zoomed page pans only as far as its edge in the viewer as it is now. Shrinking the viewer
     * after its gestures were set up (a rotation, a pane drag) moves that edge, and a drag must not
     * go on measuring against the old size, which would pull a blank margin into view.
     */
    @Test
    fun aDragAfterTheViewerShrankStopsAtThePagesEdge() {
        var viewer by mutableStateOf(DpSize(600.dp, 800.dp))
        val page = a4Page()
        var created: ImageComposeScene? = null
        onUiThread {
            created =
                ImageComposeScene(WIDTH, HEIGHT) {
                    NpnTheme(darkTheme = false) {
                        Surface {
                            PageViewer(
                                initialPage = 0,
                                pageCount = PageCountState.Ready(1),
                                render = { _, _ -> page },
                                onPageChange = {},
                                onRetryPageCount = {},
                                modifier = Modifier.size(viewer),
                            )
                        }
                    }
                }
        }
        val scene = checkNotNull(created)
        try {
            awaitPageImage(scene)
            onUiThread {
                scene.click("Zoom in")
                scene.settle()
                // A first drag, so the page's gestures are already under way when the viewer shrinks.
                scene.drag(from = Offset(300f, 400f), by = Offset(0f, 10f))
                scene.settle()
                viewer = DpSize(300.dp, 400.dp)
                scene.settle()

                scene.drag(from = Offset(150f, 200f), by = Offset(0f, 1000f))
                scene.settle()

                // At 150%, the page fitted to 300x400 is 600 high: dragged down as far as it goes,
                // its top edge meets the viewer's.
                scene.pageImageTop() shouldBe (0f plusOrMinus 1f)
            }
        } finally {
            onUiThread { scene.close() }
        }
    }

    /** Render until the page's bitmap, decoded off the UI thread, is on screen. */
    private fun awaitPageImage(scene: ImageComposeScene) {
        repeat(DECODE_POLLS) {
            var shown = false
            onUiThread {
                scene.settle()
                shown = scene.nodes().any { it.contentDescription() == "Page 1" }
            }
            if (shown) return
            Thread.sleep(DECODE_POLL_MS)
        }
        error("The page never rendered")
    }

    /** Where the zoomed page image's top edge is, its zoom and pan included and nothing clipping it. */
    private fun ImageComposeScene.pageImageTop(): Float {
        val coordinates = nodes().first { it.contentDescription() == "Page 1" }.layoutInfo.coordinates
        return coordinates.findRootCoordinates().localBoundingBoxOf(coordinates, clipBounds = false).top
    }

    /** One finger pressed at [from], moved by [by] in small steps a frame apart, then lifted. */
    private fun ImageComposeScene.drag(
        from: Offset,
        by: Offset,
    ) {
        fun touch(
            type: PointerEventType,
            at: Offset,
            pressed: Boolean,
        ) = sendPointerEvent(type, listOf(ComposeScenePointer(PointerId(0), at, pressed, PointerType.Touch)))

        touch(PointerEventType.Press, from, pressed = true)
        val steps = DRAG_STEPS
        for (step in 1..steps) {
            touch(PointerEventType.Move, from + by * (step.toFloat() / steps), pressed = true)
            clock += FRAME_NANOS
            render(clock)
        }
        touch(PointerEventType.Release, from + by, pressed = false)
    }

    /** A blank page with A4's proportions, as the renderer hands it over. */
    private fun a4Page(): ByteArray {
        val surface =
            org.jetbrains.skia.Surface
                .makeRasterN32Premul(A4_WIDTH, A4_HEIGHT)
        surface.canvas.clear(WHITE)
        return checkNotNull(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)).bytes
    }

    private var clock = 0L

    /** Render frames a frame apart until well past any page animation. */
    private fun ImageComposeScene.settle() {
        repeat(FRAMES) {
            clock += FRAME_NANOS
            render(clock)
        }
    }

    private fun ImageComposeScene.click(description: String) =
        click { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 800
        const val FRAMES = 120
        const val FRAME_NANOS = 16_000_000L
        const val DECODE_POLLS = 200
        const val DECODE_POLL_MS = 25L
        const val DRAG_STEPS = 20
        const val A4_WIDTH = 210
        const val A4_HEIGHT = 297
        const val WHITE = -1
    }
}
