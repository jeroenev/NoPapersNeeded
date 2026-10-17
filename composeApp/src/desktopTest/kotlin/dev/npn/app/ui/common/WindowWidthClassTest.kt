@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.nav.NavigationShell
import dev.npn.app.ui.nav.QueueStatus
import dev.npn.app.ui.nav.TopLevelDestination
import dev.npn.app.ui.onUiThread
import io.kotest.matchers.comparables.shouldBeLessThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** One width class for the whole window, decided by the window and read by everything in it. */
class WindowWidthClassTest {
    @Test
    fun aWindowExactlyAtTheBreakpointIsStillCompact() {
        WindowWidthClass.of(WideLayoutBreakpoint) shouldBe WindowWidthClass.COMPACT
        WindowWidthClass.of(WideLayoutBreakpoint + 1.dp) shouldBe WindowWidthClass.WIDE
    }

    @Test
    fun aScreenBesideTheRailIsWideWhenTheWindowIsEvenThoughItsOwnBoxIsNot() =
        onUiThread {
            var screenWidth: Dp? = null
            var screenClass: WindowWidthClass? = null
            render(width = 880) {
                ProvideWindowWidthClass {
                    Shell {
                        BoxWithConstraints {
                            screenWidth = maxWidth
                            screenClass = LocalWindowWidthClass.current
                        }
                    }
                }
            }

            // The rail takes its share, so the screen's box is narrower than the breakpoint...
            screenWidth.shouldNotBeNull() shouldBeLessThan WideLayoutBreakpoint
            // ...and it still lays out for the wide window the shell is drawing the rail for.
            screenClass shouldBe WindowWidthClass.WIDE
        }

    @Test
    fun aNarrowWindowIsCompactThroughout() =
        onUiThread {
            var screenClass: WindowWidthClass? = null
            render(width = 600) {
                ProvideWindowWidthClass {
                    Shell { screenClass = LocalWindowWidthClass.current }
                }
            }

            screenClass shouldBe WindowWidthClass.COMPACT
        }

    @Composable
    private fun Shell(content: @Composable () -> Unit) =
        NavigationShell(
            current = TopLevelDestination.LIBRARY,
            aiCurrent = null,
            aiConfigured = false,
            queue = QueueStatus.None,
            onNavigate = {},
            onAiChat = {},
            onAiEdit = {},
            content = content,
        )

    private fun render(
        width: Int,
        content: @Composable () -> Unit,
    ) {
        val scene = ImageComposeScene(width, HEIGHT, Density(1f), content = content)
        try {
            scene.render()
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val HEIGHT = 600
    }
}
