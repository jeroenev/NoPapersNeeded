@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui.library

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.keyShortcuts
import dev.npn.app.ui.editableField
import dev.npn.app.ui.editableText
import dev.npn.app.ui.focusFirst
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.press
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.awt.Canvas
import kotlin.test.Test
import java.awt.event.KeyEvent as AwtKeyEvent

/** The triage mode's keys on the focused mode container. */
class TriageKeysTest {
    private val ran = mutableListOf<String>()

    @Test
    fun theRightArrowMovesOnAndTheLeftArrowBack() =
        onUiThread {
            pressInTriage(Key.DirectionRight)
            pressInTriage(Key.DirectionLeft)

            ran shouldContainExactly listOf("next", "previous")
        }

    /** Letters are for typing: J and K move nowhere. */
    @Test
    fun jAndKDoNothing() =
        onUiThread {
            pressInTriage(Key.J)
            pressInTriage(Key.K)

            ran shouldContainExactly emptyList()
        }

    @Test
    fun ctrlEnterMarksReviewedAndMovesOn() =
        onUiThread {
            pressInTriage(Key.Enter, ctrl = true)
            pressInTriage(Key.Enter)

            ran shouldContainExactly listOf("review")
        }

    /** Ctrl+Enter reaches the mode from inside a field too, where the user just typed the last value. */
    @Test
    fun ctrlEnterMarksReviewedFromInsideAField() =
        onUiThread {
            val scene =
                ImageComposeScene(WIDTH * 4, HEIGHT) {
                    Box(triageKeysForTest()) {
                        var text by remember { mutableStateOf("") }
                        OutlinedTextField(text, { text = it })
                    }
                }
            scene.closing {
                render()
                focusFirst()
                press(Key.Enter, ctrl = true)
            }
            ran shouldContainExactly listOf("review")
        }

    @Test
    fun escapeLeavesTheMode() =
        onUiThread {
            pressInTriage(Key.Escape)

            ran shouldContainExactly listOf("exit")
        }

    @Test
    fun previewArrowsTakePriorityOverTheFocusedViewer() =
        onUiThread {
            val scene =
                ImageComposeScene(WIDTH, HEIGHT) {
                    Box(Modifier.triagePreviewKeys({ ran += "next" }, { ran += "previous" })) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .keyShortcuts { event ->
                                    when (event.key) {
                                        Key.DirectionRight, Key.DirectionLeft -> {
                                            { ran += "page" }
                                        }

                                        else -> {
                                            null
                                        }
                                    }
                                }.focusable(),
                        )
                    }
                }
            scene.closing {
                render()
                focusFirst()
                press(Key.DirectionRight)
                press(Key.DirectionLeft)
            }
            ran shouldContainExactly listOf("next", "previous")
        }

    /**
     * A title typed with a J or a K in it stays on its document. The keys arrive as desktop sends
     * them: the key press, then the typed character, which is all the field consumes.
     */
    @Test
    fun lettersTypedIntoAFieldDoNotMove() =
        onUiThread {
            val scene =
                ImageComposeScene(WIDTH * 4, HEIGHT) {
                    Box(triageKeysForTest()) {
                        var text by remember { mutableStateOf("") }
                        OutlinedTextField(text, { text = it })
                    }
                }
            scene.closing {
                render()
                focusFirst()
                typeOnDesktop(Key.J, AwtKeyEvent.VK_J, 'j')
                typeOnDesktop(Key.K, AwtKeyEvent.VK_K, 'k')

                editableField()?.editableText() shouldBe "jk"
            }
            ran shouldContainExactly emptyList()
        }

    private fun ImageComposeScene.typeOnDesktop(
        key: Key,
        code: Int,
        char: Char,
    ) {
        val source = Canvas()

        fun native(
            id: Int,
            keyCode: Int,
        ) = AwtKeyEvent(source, id, 0L, 0, keyCode, char)
        sendKeyEvent(
            KeyEvent(key, KeyEventType.KeyDown, char.code, nativeEvent = native(AwtKeyEvent.KEY_PRESSED, code)),
        )
        sendKeyEvent(
            KeyEvent(
                Key.Unknown,
                KeyEventType.Unknown,
                char.code,
                nativeEvent = native(AwtKeyEvent.KEY_TYPED, AwtKeyEvent.VK_UNDEFINED),
            ),
        )
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, char.code, nativeEvent = native(AwtKeyEvent.KEY_RELEASED, code)))
        render()
    }

    private fun triageKeysForTest(): Modifier =
        Modifier.triageKeys(
            onNext = { ran += "next" },
            onPrevious = { ran += "previous" },
            onReview = { ran += "review" },
            onExit = { ran += "exit" },
        )

    private fun pressInTriage(
        key: Key,
        ctrl: Boolean = false,
    ) {
        val scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                Box(
                    Modifier
                        .size(10.dp)
                        .focusable()
                        .then(triageKeysForTest()),
                )
            }
        scene.closing {
            render()
            focusFirst()
            press(key, ctrl = ctrl)
        }
    }

    private companion object {
        const val WIDTH = 100
        const val HEIGHT = 100
    }
}
