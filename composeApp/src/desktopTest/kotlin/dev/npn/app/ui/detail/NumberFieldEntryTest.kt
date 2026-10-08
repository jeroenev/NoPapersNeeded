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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.npn.app.ui.common.LocalAppLocale
import dev.npn.app.ui.common.ProvideWindowWidthClass
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.app.Doc
import dev.npn.core.app.DocListItem
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.model.ReviewMode
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.doc
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A number typed key by key into the document panel, each keystroke coming back through the draft
 * store as the screen feeds it: canonical, so `12.0` returns as `12` and `-0` as `0`.
 */
class NumberFieldEntryTest {
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
                item = DocListItem(doc("other").withAmount("7"), emptyList())
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
            editor {
                val suggested = AiMetadataState(suggestion = FieldSuggestion(mapOf(KEY to "7.25")))
                ai = AiPanelState(AiTagState(available = true), suggested)
                scene.render()
                type("12.05")
                scene.click { it.contentDescription() == "Use suggested $LABEL" }
                scene.render()
                // What the view model writes comes back later, as the document's own value.
                item = DocListItem(doc(DOC_ID).withAmount("7.25"), emptyList())
                ai = AiPanelState(AiTagState(available = true))
                scene.render()
                shown() shouldBe "7.25"
            }
        }

    private inner class Editor(
        val store: DocumentDrafts,
    ) {
        var item by mutableStateOf(DocListItem(doc(DOC_ID).withAmount("3"), emptyList()))
        var ai by mutableStateOf(AiPanelState())
        lateinit var scene: ImageComposeScene

        fun field(): SemanticsNode = scene.node { it.isEditable() && LABEL in it.texts() }

        fun shown(): String? = field().config.getOrNull(SemanticsProperties.EditableText)?.text

        fun committed(): String? = store.state[item.doc.docId]?.fields?.get(KEY)

        /** Empty the field, then type [keys] one at a time into whatever it shows. */
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

    private fun editor(
        language: String? = null,
        block: Editor.() -> Unit,
    ) {
        val owner =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        val editor = Editor(DocumentDrafts(FakeMetadataWriter(), TestScope()))
        val defs = FieldKeys.seedDefinitions { FieldKeys.SEED_LABELS.getValue(it) } + AMOUNT
        val content: @Composable () -> Unit = {
            ProvideWindowWidthClass {
                NpnTheme(darkTheme = false) {
                    Surface {
                        DocumentEditor(
                            state =
                                MetadataUiState(
                                    document =
                                        DocumentSection(
                                            item = editor.item,
                                            reviewMode = ReviewMode.INBOX_WORKFLOW,
                                            fieldDefs = defs,
                                        ),
                                    ai = editor.ai,
                                ),
                            writes = NoWrites,
                            preview = { Text("PDF preview", modifier = it) },
                            onReviewNext = {},
                            hasNext = false,
                        )
                    }
                }
            }
        }
        editor.scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides owner,
                    LocalDocumentDrafts provides editor.store,
                ) {
                    // Only when asked: the app-language local sets the JVM's locale as it is provided.
                    if (language ==
                        null
                    ) {
                        content()
                    } else {
                        CompositionLocalProvider(LocalAppLocale provides language, content)
                    }
                }
            }
        try {
            editor.scene.render()
            editor.block()
        } finally {
            editor.scene.close()
        }
    }

    private fun Doc.withAmount(value: String) = copy(fields = fields + (KEY to value))

    private fun SemanticsNode.isEditable(): Boolean = config.getOrNull(SemanticsProperties.EditableText) != null

    private fun SemanticsNode.texts(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }

    private fun SemanticsNode.contentDescription(): String? =
        config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun ImageComposeScene.node(match: (SemanticsNode) -> Boolean): SemanticsNode = nodes().first(match)

    private fun ImageComposeScene.has(match: (SemanticsNode) -> Boolean): Boolean = nodes().any(match)

    /** Click the nearest clickable node at or above the first node [match] finds. */
    private fun ImageComposeScene.click(match: (SemanticsNode) -> Boolean) {
        generateSequence(node(match)) { it.parent }
            .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            .config[SemanticsActions.OnClick]
            .action
            ?.invoke()
    }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 1600
        const val DOC_ID = "invoice"
        const val KEY = "amount"
        const val LABEL = "Amount"
        val AMOUNT =
            FieldDef(KEY, LABEL, FieldType.DECIMAL, showByDefault = true, aiEnabled = true, position = 10)
    }
}
