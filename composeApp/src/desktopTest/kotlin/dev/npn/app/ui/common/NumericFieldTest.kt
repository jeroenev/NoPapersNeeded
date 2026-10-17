@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.common

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.editableText
import dev.npn.app.ui.has
import dev.npn.app.ui.hasError
import dev.npn.app.ui.isEditable
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.app.ui.type
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * [NumericField] against a real composition, with a persisted value that comes back on its own
 * schedule, the way a settings write does.
 */
class NumericFieldTest : EnglishLocaleTest() {
    @Test
    fun aLateEchoOfAnEarlierKeystrokeDoesNotOverwriteLaterOnes() =
        onUiThread {
            var persisted by mutableIntStateOf(5)
            val committed = mutableListOf<Int>()
            withField({ persisted }, committed) { scene ->
                scene.type("1")
                scene.type("12")
                committed shouldContainExactly listOf(1, 12)

                // The first write lands only now, after the second keystroke.
                persisted = 1
                scene.render()
                scene.field()?.editableText() shouldBe "12"

                persisted = 12
                scene.render()
                scene.field()?.editableText() shouldBe "12"
            }
        }

    @Test
    fun aChangeFromElsewhereStillReplacesTheText() =
        onUiThread {
            var persisted by mutableIntStateOf(5)
            withField({ persisted }, mutableListOf()) { scene ->
                persisted = 30
                scene.render()
                scene.field()?.editableText() shouldBe "30"
            }
        }

    @Test
    fun outOfRangeInputIsMarkedAndNotCommitted() =
        onUiThread {
            val committed = mutableListOf<Int>()
            withField({ 5 }, committed) { scene ->
                scene.type("99")

                committed shouldBe emptyList()
                scene.field()?.hasError() shouldBe true
                scene.hasText("Enter a number from 1 to 50") shouldBe true
            }
        }

    private fun withField(
        value: () -> Int,
        committed: MutableList<Int>,
        block: (ImageComposeScene) -> Unit,
    ) {
        val scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                NpnTheme(darkTheme = false) {
                    Surface {
                        NumericField(
                            value = value(),
                            onValueChange = { committed += it },
                            range = 1..50,
                            label = "Batch size",
                        )
                    }
                }
            }
        try {
            scene.render()
            block(scene)
        } finally {
            scene.close()
        }
    }

    private fun ImageComposeScene.field(): SemanticsNode? = nodes().firstOrNull { it.isEditable() }

    private fun ImageComposeScene.hasText(value: String): Boolean = has { value in it.texts() }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 200
    }
}
