@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.resourcesIn
import dev.npn.app.ui.click
import dev.npn.app.ui.common.LocalAppLocale
import dev.npn.app.ui.common.ProvideWindowWidthClass
import dev.npn.app.ui.contentDescription
import dev.npn.app.ui.has
import dev.npn.app.ui.hasError
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.isEditable
import dev.npn.app.ui.isEnabled
import dev.npn.app.ui.node
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.app.ui.type
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.app.Doc
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ux_save_changes
import org.jetbrains.compose.resources.getString
import java.util.Locale
import kotlin.test.Test

/**
 * A number typed key by key into the document panel, each keystroke coming back through the draft
 * store as the screen feeds it: canonical, so `12.0` returns as `12` and `-0` as `0`.
 */
class NumberFieldEntryTest : EnglishLocaleTest() {
    @Test
    fun aDecimalIsKeptAsTypedWhileItsCanonicalFormDiffers() =
        onUiThread {
            for (typed in listOf("12.05", "-0.5")) {
                editor {
                    type(typed)
                    shown() shouldBe typed
                    committed() shouldBe typed
                }
            }
        }

    @Test
    fun aCommaLocaleDecimalIsKeptAsTyped() =
        onUiThread {
            editor(language = "nl") {
                type("1,05")
                shown() shouldBe "1,05"
                committed() shouldBe "1.05"
            }
        }

    @Test
    fun anInvalidEntryKeepsItsErrorAndBlocksTheSave() =
        onUiThread {
            editor {
                type("12.")
                shown() shouldBe "12."
                field().config.getOrNull(SemanticsProperties.Error).shouldNotBeNull()
                scene.has { it.texts().contains("Enter a number") } shouldBe true
                scene
                    .node { it.texts().contains("Save changes") && it.config.contains(SemanticsActions.OnClick) }
                    .config
                    .contains(SemanticsProperties.Disabled) shouldBe true
            }
        }

    @Test
    fun anotherDocumentReplacesTheText() =
        onUiThread {
            editor {
                type("12.")
                item = listItem(doc("other").withAmount("7"))
                scene.render()
                shown() shouldBe "7"
            }
        }

    @Test
    fun discardingTheDraftBringsBackTheSavedValue() =
        onUiThread {
            editor {
                type("12.0")
                store.discard(DOC_ID)
                scene.render()
                shown() shouldBe "3"
            }
        }

    @Test
    fun anAcceptedSuggestionReplacesTheText() =
        onUiThread {
            editor(writes = AcceptingWrites) {
                val suggested = AiMetadataState(suggestion = FieldSuggestion(mapOf(KEY to "7.25")))
                ai = AiPanelState(available = true, metadata = suggested)
                scene.render()
                type("12.05")
                scene.click { it.contentDescription() == "Use suggested $LABEL" }
                scene.render()
                // What the view model writes comes back later, as the document's own value.
                item = listItem(doc(DOC_ID).withAmount("7.25"))
                ai = AiPanelState(available = true)
                scene.render()
                shown() shouldBe "7.25"
            }
        }

    /**
     * Where a comma is the decimal point, a dot groups thousands, so `12.34` is no number to the
     * field. Nor can it be saved, though it reads as one canonically; `12,34` saves 12.34.
     */
    @Test
    fun aDotDecimalInACommaLocaleCannotBeSaved() {
        val save = DUTCH_SAVE
        onUiThread {
            editor(language = "nl") {
                type("12.34")
                shown() shouldBe "12.34"
                field().hasError() shouldBe true
                saveButton(save).shouldNotBeNull().isEnabled() shouldBe false

                type("12,34")
                field().hasError() shouldBe false
                committed() shouldBe "12.34"
                val button = saveButton(save).shouldNotBeNull()
                button.isEnabled() shouldBe true
                button.config[SemanticsActions.OnClick].action?.invoke()
                drain()
                writer.batchedEdits.single().fields shouldBe mapOf(KEY to "12.34")
            }
        }
    }

    /** `1,234` is 1.234 in Dutch, no whole number: refused, and never stored as 1234. */
    @Test
    fun aWholeNumberWithACommaInACommaLocaleCannotBeSaved() {
        val save = DUTCH_SAVE
        onUiThread {
            editor(language = "nl") {
                type("1,234", label = COUNT_LABEL)
                field(COUNT_LABEL).hasError() shouldBe true
                saveButton(save).shouldNotBeNull().isEnabled() shouldBe false
                // Nor through the save shortcut, which asks the store directly.
                store.save(DOC_ID)
                drain()
                writer.batchedEdits shouldBe emptyList()
            }
        }
    }

    /** Back on the document, a refused entry is shown as typed, with its error, not as a number. */
    @Test
    fun aRefusedEntryIsShownAsTypedWhenItsDocumentComesBack() =
        onUiThread {
            editor(language = "nl") {
                type("12.34")
                item = listItem(doc("other").withAmount("7"))
                scene.render()
                shown() shouldBe "7"
                item = listItem(doc(DOC_ID).withAmount("3"))
                scene.render()
                shown() shouldBe "12.34"
                field().hasError() shouldBe true
            }
        }

    /**
     * A refused entry that reads like the stored number is no edit back to it: it stays a draft that
     * can't be saved, and discarding it shows the stored number the locale's way again.
     */
    @Test
    fun aRefusedEntryLikeTheStoredNumberStaysUntilDiscarded() {
        val save = DUTCH_SAVE
        onUiThread {
            editor(language = "nl", amount = "12.34") {
                shown() shouldBe "12,34"
                type("12.34")
                shown() shouldBe "12.34"
                saveButton(save).shouldNotBeNull().isEnabled() shouldBe false

                store.discard(DOC_ID)
                scene.render()
                shown() shouldBe "12,34"
                field().hasError() shouldBe false
            }
        }
    }

    private inner class Editor(
        amount: String,
    ) {
        val writer = FakeMetadataWriter()

        /** Where saves run: only as far as [drain] takes them. */
        val saves = TestScope()
        val store = DocumentDrafts(writer, saves)
        var item by mutableStateOf(listItem(doc(DOC_ID).withAmount(amount)))
        var ai by mutableStateOf(AiPanelState())
        lateinit var scene: ImageComposeScene

        fun field(label: String = LABEL): SemanticsNode = scene.node { it.isEditable() && label in it.texts() }

        fun shown(label: String = LABEL): String? =
            field(label).config.getOrNull(SemanticsProperties.EditableText)?.text

        fun committed(): String? = store.state[item.doc.docId]?.fields?.get(KEY)

        /** The save bar's button, whose text is [save] in the editor's language; null while there is no draft. */
        fun saveButton(save: String = "Save changes"): SemanticsNode? =
            scene.nodes().firstOrNull { save in it.texts() && it.config.contains(SemanticsActions.OnClick) }

        /** Run the saves started so far up to their first hop back to the UI thread, which is past the write. */
        fun drain() = saves.testScheduler.runCurrent()

        /** Empty the field labelled [label], then type [keys] one at a time into whatever it shows. */
        fun type(
            keys: String,
            label: String = LABEL,
        ) {
            field(label).config[SemanticsActions.RequestFocus].action?.invoke()
            field(label).config[SemanticsActions.SetText].action?.invoke(AnnotatedString(""))
            scene.render()
            for (key in keys) {
                field(label).config[SemanticsActions.InsertTextAtCursor].action?.invoke(AnnotatedString(key.toString()))
                scene.render()
            }
        }
    }

    private fun editor(
        language: String? = null,
        amount: String = "3",
        writes: DocumentWrites = NoWrites,
        block: Editor.() -> Unit,
    ) {
        val editor = Editor(amount)
        val defs = FieldKeys.seedDefinitions { FieldKeys.SEED_LABELS.getValue(it) } + AMOUNT + COUNT
        val content: @Composable () -> Unit = {
            ProvideWindowWidthClass {
                NpnTheme(darkTheme = false) {
                    Surface {
                        DocumentEditor(
                            state =
                                MetadataUiState(
                                    document =
                                        DocumentSection(
                                            loadState = DocLoadState.Present(editor.item),
                                            reviewMode = ReviewMode.INBOX_WORKFLOW,
                                            fieldDefs = defs,
                                        ),
                                    ai = editor.ai,
                                ),
                            writes = writes,
                            preview = { Text("PDF preview", modifier = it) },
                            onReviewNext = {},
                            hasNext = false,
                        )
                    }
                }
            }
        }
        editor.scene =
            hostedScene(WIDTH, HEIGHT, LocalDocumentDrafts provides editor.store) {
                // Only when asked: the app-language local sets the JVM's locale as it is provided.
                if (language == null) {
                    content()
                } else {
                    CompositionLocalProvider(LocalAppLocale provides language, content)
                }
            }
        try {
            editor.scene.render()
            editor.block()
        } finally {
            editor.scene.close()
        }
    }

    /** An accepted suggestion whose write lands at once: `7.25` is the value the test suggests. */
    private object AcceptingWrites : DocumentWrites by NoWrites {
        override fun acceptAiMetadata(
            key: String,
            onStart: (String) -> Boolean,
            onFinished: (Boolean) -> Unit,
        ) = onFinished(onStart("7.25"))
    }

    private fun Doc.withAmount(value: String) = copy(fields = fields + (KEY to value))

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 1600
        const val DOC_ID = "invoice"
        const val KEY = "amount"
        const val LABEL = "Amount"
        const val COUNT_LABEL = "Count"
        val AMOUNT =
            FieldDef(KEY, LABEL, FieldType.DECIMAL, showByDefault = true, aiEnabled = true, position = 10)
        val COUNT =
            FieldDef("count", COUNT_LABEL, FieldType.NUMBER, showByDefault = true, aiEnabled = false, position = 11)

        /** The save bar's button in Dutch, read before a scene sets the JVM's locale. */
        val DUTCH_SAVE: String by lazy {
            runBlocking { getString(resourcesIn(Locale.forLanguageTag("nl")), Res.string.ux_save_changes) }
        }
    }
}
