@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.detail

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
import io.kotest.matchers.shouldBe
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** [PageViewer] against a real composition, with the page index fed back the way the viewmodel does. */
class PageViewerTest {
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
                                pageIndex = pageIndex,
                                pageCount = PageCountState.Ready(3),
                                render = { _, _ -> null },
                                onPageChange = { pageIndex = it },
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

    private var clock = 0L

    /** Render frames a frame apart until well past any page animation. */
    private fun ImageComposeScene.settle() {
        repeat(FRAMES) {
            clock += FRAME_NANOS
            render(clock)
        }
    }

    private fun ImageComposeScene.click(description: String) {
        val node =
            nodes().first { node ->
                node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true
            }
        generateSequence(node) { it.parent }
            .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            .config[SemanticsActions.OnClick]
            .action
            ?.invoke()
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
        const val WIDTH = 600
        const val HEIGHT = 800
        const val FRAMES = 120
        const val FRAME_NANOS = 16_000_000L
    }
}
