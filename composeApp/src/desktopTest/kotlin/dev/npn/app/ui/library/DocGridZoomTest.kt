@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.closing
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.themedScene
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.model.ReviewMode
import dev.npn.core.settings.GridSizing
import dev.npn.core.testing.FakeDocContentFacade
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test

/**
 * [DocGrid]'s zoom against a real composition, with a persisted width that comes back on its own
 * schedule, the way a settings write does.
 */
class DocGridZoomTest : EnglishLocaleTest() {
    @Test
    fun aLateEchoOfAnEarlierCommitDoesNotUndoALaterZoom() =
        onUiThread {
            var persisted by mutableIntStateOf(GridSizing.DEFAULT_COLUMN_WIDTH)
            val committed = CopyOnWriteArrayList<Int>()
            val zoom = GridZoom()
            themedScene(WIDTH, HEIGHT) {
                DocGrid(
                    results = DocumentResults(LibraryQuery(LibraryFilter(), LibrarySort()), emptyList()),
                    wiring = wiring(),
                    selectionMode = false,
                    selected = emptySet(),
                    columnWidth = persisted,
                    onColumnWidthChange = { committed += it },
                    zoom = zoom,
                )
            }.closing {
                render()
                zoomIn(zoom)
                awaitCommits(committed, 1)
                val first = committed.single()

                zoomIn(zoom)
                // The first write lands only now, after the second zoom step.
                persisted = first
                render()
                awaitCommits(committed, 2)

                // The second step is still on screen and got written, larger than the first.
                committed shouldHaveSize 2
                committed[0] shouldBe first
                committed[1] shouldBeGreaterThan first
            }
        }

    private fun wiring() =
        DocItemWiring(
            content = FakeDocContentFacade(),
            tagColors = emptyMap(),
            reviewMode = ReviewMode.UNTAGGED_ONLY,
            previewOnClick = false,
            onOpen = {},
            onToggleSelect = {},
            onSelectRange = {},
            onStartSelection = {},
            onAddTag = {},
            onRemoveTag = {},
            onTrash = {},
        )

    /** The view menu's "Larger grid items", which steps the grid through [zoom]. */
    private fun ImageComposeScene.zoomIn(zoom: GridZoom) {
        zoom.canZoomIn shouldBe true
        zoom.zoomIn()
        render()
    }

    /** Render until [count] widths were committed or the debounce has had ample time to fire. */
    private fun ImageComposeScene.awaitCommits(
        committed: List<Int>,
        count: Int,
    ) {
        val deadline = System.currentTimeMillis() + COMMIT_WAIT_MS
        while (committed.size < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_MS)
            render()
        }
    }

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 600
        const val COMMIT_WAIT_MS = 2_000L
        const val POLL_MS = 20L
    }
}
