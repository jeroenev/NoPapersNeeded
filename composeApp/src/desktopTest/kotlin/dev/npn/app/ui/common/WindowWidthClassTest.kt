@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.common

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.closing
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

    @Test
    fun crossingTheBreakpointKeepsWhatTheContentRemembers() =
        onUiThread {
            var widthClass by mutableStateOf(WindowWidthClass.WIDE)
            var created = 0
            var seenClass: WindowWidthClass? = null
            val scene =
                ImageComposeScene(880, HEIGHT, Density(1f)) {
                    CompositionLocalProvider(LocalWindowWidthClass provides widthClass) {
                        Shell {
                            remember { ++created }
                            seenClass = LocalWindowWidthClass.current
                        }
                    }
                }
            scene.closing {
                render()
                widthClass = WindowWidthClass.COMPACT
                Snapshot.sendApplyNotifications()
                render()
            }

            // The content moved from beside the rail to above the bottom bar...
            seenClass shouldBe WindowWidthClass.COMPACT
            // ...and kept its state, as a NavHost keeps its back stack.
            created shouldBe 1
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
        ImageComposeScene(width, HEIGHT, Density(1f), content = content).closing { render() }
    }

    private companion object {
        const val HEIGHT = 600
    }
}
