@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.resourcesIn
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.editableText
import dev.npn.app.ui.hasError
import dev.npn.app.ui.isEditable
import dev.npn.app.ui.isEnabled
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.themedScene
import dev.npn.app.ui.type
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_field_type_decimal
import no_papers_needed.composeapp.generated.resources.common_field_type_number
import no_papers_needed.composeapp.generated.resources.common_field_type_text
import no_papers_needed.composeapp.generated.resources.common_save
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.Test

/**
 * The document panel's "New field" dialog in Dutch, where a comma is the decimal point: what it
 * confirms is what its value editor shows and reads, never a canonical reading of refused text.
 */
class NewFieldDialogTest : EnglishLocaleTest() {
    @Test
    fun aDotDecimalCannotBeConfirmed() {
        val words = Words.dutch()
        onUiThread {
            dialog(words) {
                pickType(words.decimal)
                type("12.34")
                field().hasError() shouldBe true
                saveButton().isEnabled() shouldBe false

                type("12,34")
                field().hasError() shouldBe false
                saveButton().isEnabled() shouldBe true
                save()
                confirmed shouldBe listOf("12.34")
            }
        }
    }

    /** `1,234` typed as text is no whole number in Dutch: switching to one drops it, not stores 1234. */
    @Test
    fun aTypeSwitchDropsAValueTheNewEditorRefuses() {
        val words = Words.dutch()
        onUiThread {
            dialog(words) {
                type("1,234")
                pickType(words.number)
                shown() shouldBe ""
                field().hasError() shouldBe false
                save()
                confirmed shouldBe listOf(null)
            }
        }
    }

    /** What the new type's editor reads in what it shows is kept: `1,5` becomes 1.5. */
    @Test
    fun aTypeSwitchKeepsAValueAsTheNewEditorReadsIt() {
        val words = Words.dutch()
        onUiThread {
            dialog(words) {
                type("1,5")
                pickType(words.decimal)
                shown() shouldBe "1,5"
                field().hasError() shouldBe false
                save()
                confirmed shouldBe listOf("1.5")
            }
        }
    }

    /** The dialog's words in Dutch, read before the scene sets the JVM's locale. */
    private class Words(
        val save: String,
        val text: String,
        val number: String,
        val decimal: String,
    ) {
        companion object {
            fun dutch(): Words {
                val resources = resourcesIn(Locale.forLanguageTag("nl"))
                return runBlocking {
                    Words(
                        save = getString(resources, Res.string.common_save),
                        text = getString(resources, Res.string.common_field_type_text),
                        number = getString(resources, Res.string.common_field_type_number),
                        decimal = getString(resources, Res.string.common_field_type_decimal),
                    )
                }
            }
        }
    }

    private inner class Dialog(
        val words: Words,
    ) {
        /** The value each confirm handed on. */
        val confirmed = mutableListOf<String?>()
        lateinit var scene: ImageComposeScene

        /** The value's editor, labelled with the new field's label (the form's label field shows it as its text). */
        fun field(): SemanticsNode = scene.nodes().first { it.isEditable() && LABEL in it.texts() }

        fun shown(): String? = field().editableText()

        fun saveButton(): SemanticsNode =
            scene.nodes().first { words.save in it.texts() && it.config.contains(SemanticsActions.OnClick) }

        fun save() {
            saveButton().config[SemanticsActions.OnClick].action?.invoke()
            scene.render()
        }

        /** Open the type menu, showing the current type, and pick [type]; then let the switch settle. */
        fun pickType(type: String) {
            scene.click { words.text in it.texts() || words.number in it.texts() || words.decimal in it.texts() }
            scene.render()
            scene.click { type in it.texts() && it.config.getOrNull(SemanticsActions.OnClick) != null }
            repeat(3) { scene.render() }
        }

        /** Empty the value's editor, then type [keys] one at a time into whatever it shows. */
        fun type(keys: String) {
            field().config[SemanticsActions.RequestFocus].action?.invoke()
            field().config[SemanticsActions.SetText].action?.invoke(AnnotatedString(""))
            scene.render()
            for (key in keys) {
                field().config[SemanticsActions.InsertTextAtCursor].action?.invoke(AnnotatedString(key.toString()))
                scene.render()
            }
        }
    }

    private fun dialog(
        words: Words,
        block: Dialog.() -> Unit,
    ) {
        val dialog = Dialog(words)
        dialog.scene =
            themedScene(WIDTH, HEIGHT, appLocale = "nl") {
                NewFieldDialog(
                    seed = NewFieldSeed(key = KEY, label = LABEL),
                    existing = emptyList(),
                    keySuggestions = emptyList(),
                    onConfirm = { _, value -> dialog.confirmed += value },
                    onDismiss = {},
                )
            }
        dialog.scene.closing {
            repeat(2) { render() }
            dialog.block()
        }
    }

    private companion object {
        const val WIDTH = 900
        const val HEIGHT = 1400
        const val KEY = "bedrag"
        const val LABEL = "Bedrag"
    }
}
