@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.settings

import androidx.compose.material3.Surface
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.ui.click
import dev.npn.app.ui.isEnabled
import dev.npn.app.ui.node
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
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

    /** The owner may still show the previous Flow emission when the next key arrives. */
    @Test
    fun templateKeysAreKeptBeforeTheOwnerCatchesUp() =
        onUiThread {
            val typed = mutableListOf<String>()
            val scene =
                ImageComposeScene(WIDTH, HEIGHT) {
                    NpnTheme(darkTheme = false) {
                        Surface {
                            StoragePathRuleEditor(
                                template = "{title}.pdf",
                                enabled = false,
                                fieldTypes = emptyMap(),
                                onTemplateChange = { typed += it },
                                onEnabledChange = {},
                            )
                        }
                    }
                }
            try {
                scene.render()

                fun field() = scene.nodes().single { it.config.getOrNull(SemanticsProperties.EditableText) != null }
                field().config[SemanticsActions.RequestFocus].action?.invoke()
                scene.render()
                for (key in listOf("a", "b")) {
                    val end = field().config[SemanticsProperties.EditableText].length
                    field().config[SemanticsActions.SetSelection].action?.invoke(end, end, false)
                    scene.render()
                    field().config[SemanticsActions.InsertTextAtCursor].action?.invoke(AnnotatedString(key))
                    scene.render()
                }
                typed shouldContainExactly listOf("{title}.pdfa", "{title}.pdfab")
                field().config[SemanticsProperties.EditableText].text shouldBe "{title}.pdfab"
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
