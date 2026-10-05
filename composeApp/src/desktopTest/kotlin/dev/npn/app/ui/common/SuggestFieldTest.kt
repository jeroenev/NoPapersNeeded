@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.ui.theme.NpnTheme
import io.kotest.matchers.shouldBe
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The field-definition form's key autocomplete against a real composition. */
class SuggestFieldTest {
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

    /** The menu answers the key as typed now, not the key one keystroke earlier. */
    @Test
    fun keySuggestionsFollowTheKeystrokeJustTyped() =
        onUiThread {
            val scene = keyForm(suggestions = listOf("invoice_no", "iban"))
            try {
                scene.render()
                scene.type("q")
                scene.render()
                scene.type("i")
                scene.render()
                scene.has { it.text() == "invoice_no" } shouldBe true
            } finally {
                scene.close()
            }
        }

    /** Opening the suggestions leaves the keyboard on the key field, so the next keystroke lands in it. */
    @Test
    fun keySuggestionsDoNotTakeTheKeyboardFromTheField() =
        onUiThread {
            val scene = keyForm(suggestions = listOf("invoice_no", "iban"))
            try {
                scene.render()
                scene
                    .keyField()
                    .config[SemanticsActions.RequestFocus]
                    .action
                    ?.invoke()
                scene.render()
                scene.type("in")
                scene.render()
                scene.has { it.text() == "invoice_no" } shouldBe true
                scene.pressBackspace()
                scene.render()
                scene
                    .keyField()
                    .config
                    .getOrNull(SemanticsProperties.EditableText)
                    ?.text shouldBe "i"
            } finally {
                scene.close()
            }
        }

    private fun keyForm(suggestions: List<String>): ImageComposeScene =
        ImageComposeScene(WIDTH, HEIGHT) {
            NpnTheme(darkTheme = false) {
                var state by remember { mutableStateOf(FieldDefFormState()) }
                FieldDefForm(
                    state = state,
                    onChange = { state = it },
                    existing = emptyList(),
                    keySuggestions = suggestions,
                )
            }
        }

    private fun ImageComposeScene.keyField(): SemanticsNode =
        nodes().first { node ->
            node.config.getOrNull(SemanticsProperties.EditableText) != null &&
                node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Key" } == true
        }

    private fun ImageComposeScene.type(text: String) {
        keyField().config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
    }

    /** Sent to whatever holds the keyboard; the key-event constructor is Compose's internal one. */
    private fun ImageComposeScene.pressBackspace() {
        sendKeyEvent(KeyEvent(Key.Backspace, KeyEventType.KeyDown))
        sendKeyEvent(KeyEvent(Key.Backspace, KeyEventType.KeyUp))
    }

    private fun SemanticsNode.text(): String? =
        config
            .getOrNull(SemanticsProperties.Text)
            ?.firstOrNull()
            ?.text

    private fun ImageComposeScene.has(match: (SemanticsNode) -> Boolean): Boolean = nodes().any(match)

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
        const val HEIGHT = 900
    }
}
