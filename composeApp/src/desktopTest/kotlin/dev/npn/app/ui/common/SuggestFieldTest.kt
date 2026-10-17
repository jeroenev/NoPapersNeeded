@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.has
import dev.npn.app.ui.isEditable
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.text
import dev.npn.app.ui.texts
import dev.npn.app.ui.theme.NpnTheme
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** [SuggestField] against a real composition: the field-definition form's key autocomplete and the menu's keys. */
class SuggestFieldTest : EnglishLocaleTest() {
    /** The menu answers the key as typed now, not the key one keystroke earlier. */
    @Test
    fun keySuggestionsFollowTheKeystrokeJustTyped() =
        onUiThread {
            val scene = keyForm(suggestions = listOf("invoice_no", "iban"))
            try {
                scene.render()
                scene.typeKey("q")
                scene.render()
                scene.typeKey("i")
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
                scene.typeKey("in")
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

    /** The row the arrow keys are on stays a real row when the matches shrink under the open menu. */
    @Test
    fun enterPicksARowAfterTheMatchesShrink() =
        onUiThread {
            var suggestions by mutableStateOf((1..6).map { "item$it" })
            val picked = mutableListOf<String>()
            val scene = suggestField({ suggestions }, onPick = { picked += it })
            try {
                scene.render()
                scene.focusKeyField()
                scene.typeKey("item")
                scene.render()
                repeat(5) {
                    scene.press(Key.DirectionDown)
                    scene.render()
                }
                suggestions = listOf("item1", "item2")
                scene.render()
                scene.press(Key.Enter)
                scene.render()
                picked shouldBe listOf("item2")
            } finally {
                scene.close()
            }
        }

    /** With nothing matching there is no menu: Enter picks nothing and goes on to the form around the field. */
    @Test
    fun enterWithNoMatchesIsLeftToTheForm() =
        onUiThread {
            val picked = mutableListOf<String>()
            var formEnters = 0
            val scene =
                suggestField(
                    { listOf("invoice_no", "iban") },
                    onPick = { picked += it },
                    onFormEnter = { formEnters++ },
                )
            try {
                scene.render()
                scene.focusKeyField()
                scene.typeKey("zz")
                scene.render()
                scene.press(Key.Enter)
                scene.render()
                picked shouldBe emptyList()
                formEnters shouldBe 1
                scene
                    .keyField()
                    .config
                    .getOrNull(SemanticsProperties.EditableText)
                    ?.text shouldBe "zz"
            } finally {
                scene.close()
            }
        }

    /** A [SuggestField] labelled "Key" over whatever [suggestions] holds, inside a form that sees Enter. */
    private fun suggestField(
        suggestions: () -> List<String>,
        onPick: (String) -> Unit,
        onFormEnter: () -> Unit = {},
    ): ImageComposeScene =
        ImageComposeScene(WIDTH, HEIGHT) {
            NpnTheme(darkTheme = false) {
                var value by remember { mutableStateOf("") }
                Box(
                    Modifier.onKeyEvent { event ->
                        if (event.key == Key.Enter && event.type == KeyEventType.KeyDown) onFormEnter()
                        false
                    },
                ) {
                    SuggestField(
                        value = value,
                        onValueChange = { value = it },
                        suggestions = suggestions(),
                        label = "Key",
                        onPick = onPick,
                    )
                }
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

    private fun ImageComposeScene.keyField(): SemanticsNode = nodes().first { it.isEditable() && "Key" in it.texts() }

    private fun ImageComposeScene.typeKey(text: String) {
        keyField().config[SemanticsActions.SetText].action?.invoke(AnnotatedString(text))
    }

    private fun ImageComposeScene.focusKeyField() {
        keyField()
            .config[SemanticsActions.RequestFocus]
            .action
            ?.invoke()
        render()
    }

    private fun ImageComposeScene.pressBackspace() = press(Key.Backspace)

    /** Sent to whatever holds the keyboard; the key-event constructor is Compose's internal one. */
    private fun ImageComposeScene.press(key: Key) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp))
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 900
    }
}
