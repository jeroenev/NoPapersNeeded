@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.app.GridSizing
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.model.ReviewMode
import dev.npn.core.testing.FakeDocContentFacade
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * [DocGrid]'s zoom against a real composition, with a persisted width that comes back on its own
 * schedule, the way a settings write does.
 */
class DocGridZoomTest {
    private var previousLocale: Locale? = null

    @BeforeTest
    fun pinEnglishLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restoreLocale() {
        previousLocale?.let { Locale.setDefault(it) }
    }

    @Test
    fun aLateEchoOfAnEarlierCommitDoesNotUndoALaterZoom() =
        onUiThread {
            var persisted by mutableIntStateOf(GridSizing.DEFAULT_COLUMN_WIDTH)
            val committed = CopyOnWriteArrayList<Int>()
            val scene =
                ImageComposeScene(WIDTH, HEIGHT) {
                    NpnTheme(darkTheme = false) {
                        Surface {
                            DocGrid(
                                results = DocumentResults(LibraryQuery(LibraryFilter(), LibrarySort()), emptyList()),
                                wiring = wiring(),
                                selectionMode = false,
                                selected = emptySet(),
                                columnWidth = persisted,
                                onColumnWidthChange = { committed += it },
                            )
                        }
                    }
                }
            try {
                scene.render()
                scene.zoomIn()
                scene.awaitCommits(committed, 1)
                val first = committed.single()

                scene.zoomIn()
                // The first write lands only now, after the second zoom step.
                persisted = first
                scene.render()
                scene.awaitCommits(committed, 2)

                // The second step is still on screen and got written, larger than the first.
                committed shouldHaveSize 2
                committed[0] shouldBe first
                committed[1] shouldBeGreaterThan first
            } finally {
                scene.close()
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
            onStartSelection = {},
            onAddTag = {},
            onRemoveTag = {},
            onTrash = {},
        )

    private fun ImageComposeScene.zoomIn() {
        nodes()
            .first { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == "Larger grid items" }
            .let { generateSequence(it) { node -> node.parent } }
            .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            .config[SemanticsActions.OnClick]
            .action
            ?.invoke()
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

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private companion object {
        const val WIDTH = 1200
        const val HEIGHT = 600
        const val COMMIT_WAIT_MS = 2_000L
        const val POLL_MS = 20L
    }
}
