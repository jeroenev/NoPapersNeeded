@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.test.Test

/**
 * The detail screen's keys as its nodes nest them: document paging around the whole screen, and
 * the page viewer or the pane divider holding focus inside it.
 */
class DocumentKeysTest {
    private val ran = mutableListOf<String>()

    /** What holds focus inside the screen. */
    private enum class Focused { VIEWER, DIVIDER }

    /** Ctrl+Left/Right walk to another document while the viewer has focus, as it has on entry. */
    @Test
    fun ctrlArrowsInTheViewerWalkToAnotherDocument() =
        onUiThread {
            press(Focused.VIEWER, Key.DirectionRight, ctrl = true)
            press(Focused.VIEWER, Key.DirectionLeft, ctrl = true)

            ran shouldContainExactly listOf("next document", "previous document")
        }

    @Test
    fun plainArrowsInTheViewerTurnThePage() =
        onUiThread {
            press(Focused.VIEWER, Key.DirectionRight)
            press(Focused.VIEWER, Key.DirectionLeft)

            ran shouldContainExactly listOf("page 2", "page 0")
        }

    /** The pane divider moves on a plain arrow and leaves Ctrl+arrows to document paging too. */
    @Test
    fun ctrlArrowsOnThePaneDividerWalkToAnotherDocument() =
        onUiThread {
            press(Focused.DIVIDER, Key.DirectionRight, ctrl = true)
            press(Focused.DIVIDER, Key.DirectionRight)

            ran shouldContainExactly listOf("next document", "grow")
        }

    private fun press(
        focused: Focused,
        key: Key,
        ctrl: Boolean = false,
    ) {
        val scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                Box(
                    Modifier
                        .size(50.dp)
                        .docPagingKeys(
                            onPrevDoc = { ran += "previous document" },
                            onNextDoc = { ran += "next document" },
                        ),
                ) {
                    val pager = rememberPagerState(initialPage = 1) { PAGES }
                    val keys =
                        when (focused) {
                            Focused.VIEWER -> {
                                Modifier.viewerKeys(
                                    pagerState = pager,
                                    pageCount = PAGES,
                                    transform = { ViewerTransform() },
                                    onTransform = {},
                                    containerSize = { Size.Zero },
                                    fittedSize = { Size.Zero },
                                    onPage = { ran += "page $it" },
                                )
                            }

                            Focused.DIVIDER -> {
                                Modifier.paneResizeKeys(onShrink = { ran += "shrink" }, onGrow = { ran += "grow" })
                            }
                        }
                    Box(Modifier.size(10.dp).focusable().then(keys))
                }
            }
        try {
            scene.render()
            scene
                .node { it.config.getOrNull(SemanticsActions.RequestFocus) != null }
                .config[SemanticsActions.RequestFocus]
                .action
                ?.invoke()
            scene.render()
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isCtrlPressed = ctrl))
            scene.sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isCtrlPressed = ctrl))
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val WIDTH = 100
        const val HEIGHT = 100
        const val PAGES = 3
    }
}
