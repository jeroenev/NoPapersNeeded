@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
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
import dev.npn.app.ui.closing
import dev.npn.app.ui.has
import dev.npn.app.ui.isEditable
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.press
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
            keyForm(suggestions = listOf("invoice_no", "iban")).closing {
                render()
                typeKey("q")
                render()
                typeKey("i")
                render()
                has { it.text() == "invoice_no" } shouldBe true
            }
        }

    /** Opening the suggestions leaves the keyboard on the key field, so the next keystroke lands in it. */
    @Test
    fun keySuggestionsDoNotTakeTheKeyboardFromTheField() =
        onUiThread {
            keyForm(suggestions = listOf("invoice_no", "iban")).closing {
                render()
                keyField()
                    .config[SemanticsActions.RequestFocus]
                    .action
                    ?.invoke()
                render()
                typeKey("in")
                render()
                has { it.text() == "invoice_no" } shouldBe true
                press(Key.Backspace)
                render()
                keyField()
                    .config
                    .getOrNull(SemanticsProperties.EditableText)
                    ?.text shouldBe "i"
            }
        }

    /** The row the arrow keys are on stays a real row when the matches shrink under the open menu. */
    @Test
    fun enterPicksARowAfterTheMatchesShrink() =
        onUiThread {
            var suggestions by mutableStateOf((1..6).map { "item$it" })
            val picked = mutableListOf<String>()
            suggestField({ suggestions }, onPick = { picked += it }).closing {
                render()
                focusKeyField()
                typeKey("item")
                render()
                repeat(5) {
                    press(Key.DirectionDown)
                    render()
                }
                suggestions = listOf("item1", "item2")
                render()
                press(Key.Enter)
                render()
                picked shouldBe listOf("item2")
            }
        }

    /** With nothing matching there is no menu: Enter picks nothing and goes on to the form around the field. */
    @Test
    fun enterWithNoMatchesIsLeftToTheForm() =
        onUiThread {
            val picked = mutableListOf<String>()
            var formEnters = 0
            suggestField(
                { listOf("invoice_no", "iban") },
                onPick = { picked += it },
                onFormEnter = { formEnters++ },
            ).closing {
                render()
                focusKeyField()
                typeKey("zz")
                render()
                press(Key.Enter)
                render()
                picked shouldBe emptyList()
                formEnters shouldBe 1
                keyField()
                    .config
                    .getOrNull(SemanticsProperties.EditableText)
                    ?.text shouldBe "zz"
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

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 900
    }
}
