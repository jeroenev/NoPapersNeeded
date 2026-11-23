@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertTrue

/** The compact detail's peek sheet: collapsed summary, tap to expand, and Back collapsing first. */
class PeekSheetTest {
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
                state.expand()
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

    private infix fun List<String>.shouldContainText(value: String) = apply { (value in this) shouldBe true }

    private fun ImageComposeScene.texts(): List<String> = nodes().flatMap { it.texts() }

    private companion object {
        const val WIDTH = 360
        const val HEIGHT = 740
    }
}
