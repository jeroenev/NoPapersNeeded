@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.LocalReducedMotion
import dev.npn.app.ui.node
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.press
import dev.npn.app.ui.texts
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertTrue

/** The compact detail's peek sheet: collapsed summary, tap to expand, and Back collapsing first. */
class PeekSheetTest : EnglishLocaleTest() {
    @Test
    fun previewControlsRemainAboveSheetAndLongTagsLeaveRoomForEditor() =
        onUiThread {
            val state = PeekSheetState()
            val scene =
                ImageComposeScene(WIDTH, HEIGHT) {
                    PeekDocumentSheet(
                        item = listItem(doc("invoice", title = "Invoice"), (1..30).map { "Long tag number $it" }),
                        tagColors = emptyMap(),
                        state = state,
                        preview = { modifier ->
                            androidx.compose.foundation.layout.Box(modifier) {
                                Text("Preview controls", Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                            }
                        },
                        metadata = { Text("Metadata editor", modifier = it) },
                        statusBar = {},
                    )
                }
            scene.closing {
                render()
                val controls = nodes().first { "Preview controls" in it.texts() }
                val title = nodes().first { "Invoice" in it.texts() }
                assertTrue(controls.boundsInRoot.bottom <= title.boundsInRoot.top)
                state.set(true)
                repeat(30) { render((it + 1) * 16_000_000L) }
                val editor = nodes().first { "Metadata editor" in it.texts() }
                assertTrue(editor.boundsInRoot.height > 100f)
            }
        }

    @Test
    fun tappingTheSummaryExpandsTheMetadataAndBackCollapsesItBeforeLeaving() =
        onUiThread {
            var fellThrough = 0
            val dispatcher = NavigationEventDispatcher(onBackCompletedFallback = { fellThrough++ })
            val back = DirectNavigationEventInput().also(dispatcher::addInput)
            val owner =
                object : NavigationEventDispatcherOwner {
                    override val navigationEventDispatcher = dispatcher
                }
            val state = PeekSheetState()
            val item =
                listItem(
                    doc("invoice", title = "Electricity invoice", docDate = "2026-03-01"),
                    listOf("Utilities"),
                )
            val scene =
                ImageComposeScene(WIDTH, HEIGHT) {
                    CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides owner) {
                        PeekDocumentSheet(
                            item = item,
                            tagColors = emptyMap(),
                            state = state,
                            preview = { Text("PDF preview", modifier = it) },
                            metadata = { Text("Metadata editor", modifier = it) },
                            statusBar = { },
                            modifier = Modifier,
                        )
                    }
                }
            scene.closing {
                render()
                // Collapsed: the preview and the summary, but not the editor.
                scene.texts() shouldContainText "PDF preview"
                scene.texts() shouldContainText "Electricity invoice"
                scene.texts() shouldContainText "2026-03-01"
                scene.texts() shouldContainText "Utilities"
                (("Metadata editor" in scene.texts())) shouldBe false

                scene.click { "Electricity invoice" in it.texts() }
                render()
                state.expanded shouldBe true
                scene.texts() shouldContainText "Metadata editor"

                back.backCompleted()
                render()
                // The first Back collapses the sheet; it does not reach the detail route.
                state.expanded shouldBe false
                fellThrough shouldBe 0
                (("Metadata editor" in scene.texts())) shouldBe false

                back.backCompleted()
                fellThrough shouldBe 1
            }
        }

    /**
     * The sheet's handle tells a screen reader whether the details are showing, and offers the
     * move it does not show as an action, as a tap on it toggles them.
     */
    @Test
    fun theSheetSaysWhetherItIsExpandedAndOffersExpandAndCollapse() =
        onUiThread {
            val state = PeekSheetState()
            sheetScene(state).closing {
                render()
                var handle = node { it.stateDescription() != null }
                handle.stateDescription() shouldBe "Collapsed"
                handle.config.getOrNull(SemanticsActions.Collapse) shouldBe null
                val expand = handle.config[SemanticsActions.Expand]
                expand.label shouldBe "Show details"
                expand.action?.invoke()
                render()
                state.expanded shouldBe true

                handle = node { it.stateDescription() != null }
                handle.stateDescription() shouldBe "Expanded"
                handle.config.getOrNull(SemanticsActions.Expand) shouldBe null
                val collapse = handle.config[SemanticsActions.Collapse]
                collapse.label shouldBe "Hide details"
                collapse.action?.invoke()
                render()
                state.expanded shouldBe false
            }
        }

    /**
     * The expanded sheet covers the preview's controls, so neither a screen reader nor Tab reaches
     * them, and focus that was in the preview moves to the sheet. Collapsed, they are back.
     */
    @Test
    fun anExpandedSheetHidesTheCoveredPreviewFromScreenReadersAndFocus() =
        onUiThread {
            val state = PeekSheetState()
            var previewFocused = false
            sheetScene(
                state,
                preview = { modifier ->
                    Box(modifier) {
                        Button(
                            onClick = {},
                            modifier =
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .onFocusChanged { previewFocused = it.isFocused },
                        ) { Text("Preview controls") }
                    }
                },
                metadata = { modifier ->
                    Column(modifier) {
                        Button(onClick = {}) { Text("Editor field") }
                        Button(onClick = {}) { Text("Another field") }
                    }
                },
            ).closing {
                render()
                node { "Preview controls" in it.texts() }.requestFocus()
                render()
                previewFocused shouldBe true

                state.set(true)
                frames()
                previewFocused shouldBe false
                nodes().none { "Preview controls" in it.texts() } shouldBe true
                node { it.stateDescription() != null }.isFocused() shouldBe true
                repeat(TABS) {
                    press(Key.Tab)
                    render()
                    previewFocused shouldBe false
                }

                state.set(false)
                frames()
                nodes().any { "Preview controls" in it.texts() } shouldBe true
            }
        }

    /** With reduced motion the sheet takes its expanded height at once; otherwise it slides there. */
    @Test
    fun reducedMotionExpandsTheSheetAtOnce() =
        onUiThread {
            fun editorHeight(
                reduced: Boolean,
                frames: Int,
            ): Float {
                val state = PeekSheetState()
                return sheetScene(state, reduced = reduced).closing {
                    render(0)
                    state.set(true)
                    for (frame in 1..frames) render(frame * FRAME_NANOS)
                    node { "Metadata editor" in it.texts() }.boundsInRoot.height
                }
            }
            val settled = editorHeight(reduced = false, frames = SETTLE_FRAMES)
            editorHeight(reduced = true, frames = 2) shouldBe settled
            (editorHeight(reduced = false, frames = 2) < settled) shouldBe true
        }

    private fun sheetScene(
        state: PeekSheetState,
        reduced: Boolean = false,
        preview: @Composable (Modifier) -> Unit = { Text("PDF preview", modifier = it) },
        metadata: @Composable (Modifier) -> Unit = { Text("Metadata editor", modifier = it) },
    ): ImageComposeScene =
        ImageComposeScene(WIDTH, HEIGHT) {
            CompositionLocalProvider(LocalReducedMotion provides reduced) {
                PeekDocumentSheet(
                    item = listItem(doc("invoice", title = "Electricity invoice"), listOf("Utilities")),
                    tagColors = emptyMap(),
                    state = state,
                    preview = preview,
                    metadata = metadata,
                    statusBar = {},
                )
            }
        }

    private var clock = 0L

    /** Render frames a frame apart until well past the sheet's animation. */
    private fun ImageComposeScene.frames() {
        repeat(SETTLE_FRAMES) {
            clock += FRAME_NANOS
            render(clock)
        }
    }

    private fun SemanticsNode.stateDescription(): String? = config.getOrNull(SemanticsProperties.StateDescription)

    private fun SemanticsNode.isFocused(): Boolean = config.getOrNull(SemanticsProperties.Focused) == true

    private fun SemanticsNode.requestFocus() {
        config[SemanticsActions.RequestFocus].action?.invoke()
    }

    private infix fun List<String>.shouldContainText(value: String) = apply { (value in this) shouldBe true }

    private fun ImageComposeScene.texts(): List<String> = nodes().flatMap { it.texts() }

    private companion object {
        const val WIDTH = 360
        const val HEIGHT = 740
        const val FRAME_NANOS = 16_000_000L
        const val SETTLE_FRAMES = 30
        const val TABS = 6
    }
}
