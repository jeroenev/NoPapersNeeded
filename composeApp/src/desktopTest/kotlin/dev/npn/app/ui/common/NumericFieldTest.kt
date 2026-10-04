@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.common

import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.ui.theme.NpnTheme
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * [NumericField] against a real composition, with a persisted value that comes back on its own
 * schedule, the way a settings write does.
 */
class NumericFieldTest {
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
                scene.field()?.text() shouldBe "12"

                persisted = 12
                scene.render()
                scene.field()?.text() shouldBe "12"
            }
        }

    @Test
    fun aChangeFromElsewhereStillReplacesTheText() =
        onUiThread {
            var persisted by mutableIntStateOf(5)
            withField({ persisted }, mutableListOf()) { scene ->
                persisted = 30
                scene.render()
                scene.field()?.text() shouldBe "30"
            }
        }

    @Test
    fun outOfRangeInputIsMarkedAndNotCommitted() =
        onUiThread {
            val committed = mutableListOf<Int>()
            withField({ 5 }, committed) { scene ->
                scene.type("99")

                committed shouldBe emptyList()
                scene.field()?.config?.getOrNull(SemanticsProperties.Error) shouldNotBe null
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
                            maxDigits = 3,
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

    private fun ImageComposeScene.type(value: String) {
        field()
            ?.config
            ?.get(SemanticsActions.SetText)
            ?.action
            ?.invoke(AnnotatedString(value))
        render()
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun ImageComposeScene.field(): SemanticsNode? =
        nodes().firstOrNull { it.config.getOrNull(SemanticsProperties.EditableText) != null }

    private fun SemanticsNode.text(): String? = config.getOrNull(SemanticsProperties.EditableText)?.text

    private fun ImageComposeScene.hasText(value: String): Boolean =
        nodes().any { node -> node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private companion object {
        const val WIDTH = 400
        const val HEIGHT = 200
    }
}
