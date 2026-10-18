@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.ProvideWindowWidthClass
import dev.npn.app.ui.common.WindowWidthClass
import dev.npn.app.ui.editableText
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.ocr.OcrEngineRef
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Render real Compose layouts at phone and desktop widths without a window or external service. */
class DocumentLayoutTest : EnglishLocaleTest() {
    @Test
    fun desktopShowsPdfBesideEditableDetails() =
        onUiThread {
            scene(1280, 800).closing {
                render()
                val preview = text("PDF preview")
                val title = text("Title")
                assertNotNull(preview)
                assertNotNull(title)
                assertTrue(preview.boundsInRoot.right < title.boundsInRoot.left)
                save(this, "desktop-document")
            }
        }

    @Test
    fun phoneUsesTabsAndKeepsDetailsInsideViewport() =
        onUiThread {
            scene(360, 740, 1.3f).closing {
                render()
                assertNotNull(text("PDF preview"))
                val details =
                    nodes().first { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Details" } == true &&
                            node.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                details.config[SemanticsActions.OnClick].action?.invoke()
                render()
                val title = text("Title")
                assertNotNull(title)
                assertTrue(title.boundsInRoot.left >= 0 && title.boundsInRoot.right <= 360)
                val saveButton = text("Save changes")
                val reviewButton = text("Mark reviewed & next")
                assertNotNull(saveButton)
                assertNotNull(reviewButton)
                assertTrue(saveButton.boundsInRoot.bottom <= 740)
                assertTrue(reviewButton.boundsInRoot.bottom <= 740)
                assertTrue(reviewButton.boundsInRoot.right <= 360)
                save(this, "phone-document")
            }
        }

    /**
     * The draft store treats a value that only differs in surrounding whitespace as no change, so
     * typing a space at the end of an existing value puts nothing in the draft and leaves the
     * store's value untouched. The field must still show that space: rendering straight off the
     * store would swallow it, and the next character would land against the previous word.
     */
    @Test
    fun typingASpaceAtTheEndOfAValueIsNotSwallowed() =
        onUiThread {
            scene(1280, 800).closing {
                render()
                val title = editable("Electricity invoice 2026")
                assertNotNull(title)
                title.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Electricity invoice "))
                render()
                assertNotNull(editable("Electricity invoice "))
            }
        }

    /**
     * A field being defined from the panel keeps its dialog, and what is typed into it, when the
     * window crosses the wide/compact breakpoint: the panel's tabs move to the other layout rather
     * than being built again there.
     */
    @Test
    fun anOpenNewFieldDialogKeepsWhatWasTypedAcrossTheBreakpoint() =
        onUiThread {
            val widthClass = mutableStateOf(WindowWidthClass.WIDE)
            scene(1280, 800, widthClass = widthClass).closing {
                render()
                click { "Details" in it.texts() }
                render()
                click { "Add field" in it.texts() }
                render()
                click { "New field…" in it.texts() }
                render()
                assertNotNull(text("New field"))
                nodes()
                    .last { it.config.getOrNull(SemanticsActions.SetText) != null }
                    .config[SemanticsActions.SetText]
                    .action
                    ?.invoke(AnnotatedString("Invoice no."))
                render()

                widthClass.value = WindowWidthClass.COMPACT
                render()
                render()
                assertNotNull(text("New field"))
                assertNotNull(editable("Invoice no."))
            }
        }

    /**
     * Accepting a suggestion overwrites the value on screen, so the row says what it replaces, as
     * the bulk Review does. On screen means the draft: what the user typed is what gets replaced.
     */
    @Test
    fun anAiSuggestionShowsTheValueItReplaces() =
        onUiThread {
            val suggested =
                AiPanelState(
                    available = true,
                    metadata = AiMetadataState(suggestion = FieldSuggestion(mapOf(FieldKeys.TITLE to "March bill"))),
                )
            scene(1280, 800, ai = suggested).closing {
                render()
                assertNotNull(text("March bill"))
                assertNotNull(text("Replaces: Electricity invoice 2026"))
            }
        }

    private fun scene(
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        ai: AiPanelState = AiPanelState(),
        /** The window's width class, when the test changes it; else the scene's width decides. */
        widthClass: State<WindowWidthClass>? = null,
    ): ImageComposeScene {
        val store = DocumentDrafts(FakeMetadataWriter(), CoroutineScope(Dispatchers.Unconfined))
        // An actual change, so the scene renders the save bar in its unsaved state.
        store.field("invoice", FieldKeys.TITLE, "Electricity invoice 2026", persisted = "Electricity invoice")
        return hostedScene(width, height, LocalDocumentDrafts provides store, density = Density(1f, fontScale)) {
            // The scene is the window: wide or compact from its width, as the app root decides.
            // The panel's tab, kept as the document's viewmodel keeps it.
            var shownTab by remember { mutableStateOf<PanelTab?>(null) }
            val writes =
                remember {
                    object : DocumentWrites by NoWrites {
                        override fun showTab(tab: PanelTab) {
                            shownTab = tab
                        }
                    }
                }
            ProvideWidthClass(widthClass?.value) {
                NpnTheme(darkTheme = false) {
                    Surface {
                        DocumentEditor(
                            state =
                                MetadataUiState(
                                    document =
                                        DocumentSection(
                                            loadState =
                                                DocLoadState.Present(
                                                    listItem(
                                                        doc("invoice", title = "Electricity invoice"),
                                                        listOf("Utilities"),
                                                    ),
                                                ),
                                            reviewMode = ReviewMode.INBOX_WORKFLOW,
                                            // The panel renders fields from the registry; seed it as a
                                            // library would.
                                            fieldDefs =
                                                FieldKeys.seedDefinitions {
                                                    FieldKeys.SEED_LABELS.getValue(
                                                        it,
                                                    )
                                                },
                                        ),
                                    ai = ai,
                                    tab = shownTab,
                                ),
                            writes = writes,
                            preview = { Text("PDF preview", modifier = it) },
                            onReviewNext = {},
                            hasNext = true,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ProvideWidthClass(
        widthClass: WindowWidthClass?,
        content: @Composable () -> Unit,
    ) {
        if (widthClass == null) {
            ProvideWindowWidthClass(content)
        } else {
            CompositionLocalProvider(LocalWindowWidthClass provides widthClass, content = content)
        }
    }

    private fun ImageComposeScene.text(value: String): SemanticsNode? = nodes().firstOrNull { value in it.texts() }

    /** The text field currently holding [value], by its editable content rather than its label. */
    private fun ImageComposeScene.editable(value: String): SemanticsNode? =
        nodes().firstOrNull {
            it.editableText() ==
                value
        }

    private fun save(
        scene: ImageComposeScene,
        name: String,
    ) {
        val target = File("build/reports/ui/$name.png")
        target.parentFile.mkdirs()
        scene.render().use { image -> image.encodeToData()?.use { target.writeBytes(it.bytes) } }
    }
}

/** What is on screen is under test, not the writes: every action the panel offers does nothing. */
internal object NoWrites : DocumentWrites {
    override fun addTag(tag: String) = Unit

    override fun removeTag(tag: String) = Unit

    override fun addTagWithStyle(
        tag: String,
        color: String?,
    ) = Unit

    override fun defineField(
        def: FieldDef,
        value: String?,
    ) = Unit

    override fun setOcrEngine(engine: OcrEngineRef?) = Unit

    override fun suggestAiTags() = Unit

    override fun acceptAiTag(tag: String) = Unit

    override fun dismissAiTag(tag: String) = Unit

    override fun clearAiTagSuggestions() = Unit

    override fun suggestAiMetadata() = Unit

    override fun acceptAiMetadata(
        key: String,
        onStart: (String) -> Boolean,
        onFinished: (Boolean) -> Unit,
    ) = Unit

    override fun dismissAiMetadata(key: String) = Unit

    override fun loadHistory(refresh: Boolean) = Unit

    override fun showTab(tab: PanelTab) = Unit
}
