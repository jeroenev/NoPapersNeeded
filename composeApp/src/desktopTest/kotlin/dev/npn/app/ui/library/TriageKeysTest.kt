@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.closing
import dev.npn.app.ui.focusFirst
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.press
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.test.Test

/** The triage mode's keys on the focused mode container. */
class TriageKeysTest {
    private val ran = mutableListOf<String>()

    @Test
    fun jAndTheRightArrowMoveOnKAndTheLeftArrowBack() =
        onUiThread {
            pressInTriage(Key.J)
            pressInTriage(Key.DirectionRight)
            pressInTriage(Key.K)
            pressInTriage(Key.DirectionLeft)

            ran shouldContainExactly listOf("next", "next", "previous", "previous")
        }

    @Test
    fun escapeLeavesTheMode() =
        onUiThread {
            pressInTriage(Key.Escape)

            ran shouldContainExactly listOf("exit")
        }

    /**
     * A focused child that uses a key first keeps it: this is what lets a text field or its
     * suggestion menu take J and Escape, while the keys it leaves still reach the mode.
     */
    @Test
    fun aFocusedChildTakesTheKeysItUsesFirst() =
        onUiThread {
            val scene =
                ImageComposeScene(WIDTH, HEIGHT) {
                    Box(
                        Modifier.triageKeys(onNext = { ran += "next" }, onPrevious = { ran += "previous" }, onExit = {
                            ran +=
                                "exit"
                        }),
                    ) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .focusable()
                                .onKeyEvent { event ->
                                    if (event.key == Key.J) {
                                        ran += "child J"
                                        true
                                    } else {
                                        false
                                    }
                                },
                        )
                    }
                }
            scene.closing {
                render()
                focusFirst()
                press(Key.J)
                press(Key.K)
            }

            ran shouldContainExactly listOf("child J", "previous")
        }

    private fun pressInTriage(key: Key) {
        val scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                Box(
                    Modifier
                        .size(10.dp)
                        .focusable()
                        .triageKeys(
                            onNext = { ran += "next" },
                            onPrevious = { ran += "previous" },
                            onExit = { ran += "exit" },
                        ),
                )
            }
        scene.closing {
            render()
            focusFirst()
            press(key)
        }
    }

    private companion object {
        const val WIDTH = 100
        const val HEIGHT = 100
    }
}
