@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.npn.app.ui.common.ProvideWindowWidthClass
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.app.DocListItem
import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.doc
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Render real Compose layouts at phone and desktop widths without a window or external service. */
class DocumentLayoutTest {
    private var previousLocale: Locale? = null

    /**
     * Compose resources resolve against the JVM default locale, and the assertions below name
     * strings from `values/`. Pin English so the test does not depend on the machine's locale.
     */
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
    fun desktopShowsPdfBesideEditableDetails() =
        onUiThread {
            val scene = scene(1280, 800)
            try {
                scene.render()
                val preview = scene.text("PDF preview")
                val title = scene.text("Title")
                assertNotNull(preview)
                assertNotNull(title)
                assertTrue(preview.boundsInRoot.right < title.boundsInRoot.left)
                save(scene, "desktop-document")
            } finally {
                scene.close()
            }
        }

    @Test
    fun phoneUsesTabsAndKeepsDetailsInsideViewport() =
        onUiThread {
            val scene = scene(360, 740, 1.3f)
            try {
                scene.render()
                assertNotNull(scene.text("PDF preview"))
                val details =
                    scene.nodes().first { node ->
                        node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == "Details" } == true &&
                            node.config.getOrNull(SemanticsActions.OnClick) != null
                    }
                details.config[SemanticsActions.OnClick].action?.invoke()
                scene.render()
                val title = scene.text("Title")
                assertNotNull(title)
                assertTrue(title.boundsInRoot.left >= 0 && title.boundsInRoot.right <= 360)
                val saveButton = scene.text("Save changes")
                val reviewButton = scene.text("Mark reviewed & next")
                assertNotNull(saveButton)
                assertNotNull(reviewButton)
                assertTrue(saveButton.boundsInRoot.bottom <= 740)
                assertTrue(reviewButton.boundsInRoot.bottom <= 740)
                assertTrue(reviewButton.boundsInRoot.right <= 360)
                save(scene, "phone-document")
            } finally {
                scene.close()
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
            val scene = scene(1280, 800)
            try {
                scene.render()
                val title = scene.editable("Electricity invoice 2026")
                assertNotNull(title)
                title.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Electricity invoice "))
                scene.render()
                assertNotNull(scene.editable("Electricity invoice "))
            } finally {
                scene.close()
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
            val scene = scene(1280, 800, ai = suggested)
            try {
                scene.render()
                assertNotNull(scene.text("March bill"))
                assertNotNull(scene.text("Replaces: Electricity invoice 2026"))
            } finally {
                scene.close()
            }
        }

    private fun scene(
        width: Int,
        height: Int,
        fontScale: Float = 1f,
        ai: AiPanelState = AiPanelState(),
    ): ImageComposeScene {
        val owner =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry
                        .createUnsafe(
                            this,
                        ).apply { currentState = Lifecycle.State.RESUMED }
            }
        val store = DocumentDrafts(FakeMetadataWriter(), CoroutineScope(Dispatchers.Unconfined))
        // An actual change, so the scene renders the save bar in its unsaved state.
        store.field("invoice", FieldKeys.TITLE, "Electricity invoice 2026", persisted = "Electricity invoice")
        return ImageComposeScene(width, height, density = Density(1f, fontScale)) {
            CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalDocumentDrafts provides store) {
                // The scene is the window: wide or compact from its width, as the app root decides.
                ProvideWindowWidthClass {
                    NpnTheme(darkTheme = false) {
                        Surface {
                            DocumentEditor(
                                state =
                                    MetadataUiState(
                                        document =
                                            DocumentSection(
                                                loadState =
                                                    DocLoadState.Present(
                                                        DocListItem(
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
                                    ),
                                writes = NoWrites,
                                preview = { Text("PDF preview", modifier = it) },
                                onReviewNext = {},
                                hasNext = true,
                            )
                        }
                    }
                }
            }
        }
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun ImageComposeScene.text(value: String): SemanticsNode? =
        nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == value } == true
        }

    /** The text field currently holding [value], by its editable content rather than its label. */
    private fun ImageComposeScene.editable(value: String): SemanticsNode? =
        nodes().firstOrNull { node ->
            node.config.getOrNull(SemanticsProperties.EditableText)?.text == value
        }

    private fun save(
        scene: ImageComposeScene,
        name: String,
    ) {
        val target = File("build/reports/ui/$name.png")
        target.parentFile.mkdirs()
        scene.render().use { image -> image.encodeToData()?.use { target.writeBytes(it.bytes) } }
    }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
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

    override fun setOcrEngine(engine: String?) = Unit

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
}
