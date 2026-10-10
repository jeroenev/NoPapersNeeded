@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.npn.app.ui.detail.click
import dev.npn.app.ui.detail.isEnabled
import dev.npn.app.ui.detail.node
import dev.npn.app.ui.detail.onUiThread
import dev.npn.app.ui.theme.NpnTheme
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The storage-path rule's switch: an invalid template keeps it from being armed, never from being disarmed. */
class StoragePathRuleEditorTest {
    private val switched = mutableListOf<Boolean>()

    /** A template edited into something invalid while the rule is on must not trap the rule on. */
    @Test
    fun anArmedRuleWithAnInvalidTemplateCanBeSwitchedOff() =
        onUiThread {
            val scene = scene(enabled = true)
            try {
                scene.render()
                scene.node(::isSwitch).isEnabled() shouldBe true

                scene.click(::isSwitch)

                switched shouldContainExactly listOf(false)
            } finally {
                scene.close()
            }
        }

    @Test
    fun aRuleThatIsOffCannotBeArmedWithAnInvalidTemplate() =
        onUiThread {
            val scene = scene(enabled = false)
            try {
                scene.render()
                scene.node(::isSwitch).isEnabled() shouldBe false
            } finally {
                scene.close()
            }
        }

    private fun isSwitch(node: SemanticsNode): Boolean =
        node.config.getOrNull(SemanticsProperties.ToggleableState) != null

    private fun scene(enabled: Boolean): ImageComposeScene =
        ImageComposeScene(WIDTH, HEIGHT) {
            NpnTheme(darkTheme = false) {
                Surface {
                    StoragePathRuleEditor(
                        template = INVALID_TEMPLATE,
                        enabled = enabled,
                        fieldTypes = emptyMap(),
                        onTemplateChange = {},
                        onEnabledChange = { switched += it },
                    )
                }
            }
        }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 800

        /** An opening brace with no closing one. */
        const val INVALID_TEMPLATE = "{title"
    }
}
