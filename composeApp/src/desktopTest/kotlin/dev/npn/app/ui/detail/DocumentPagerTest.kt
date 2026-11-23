@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui.detail

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.KeyedViewModelStores
import dev.npn.app.ui.common.ViewModelStoreLease
import dev.npn.app.ui.editableText
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.texts
import dev.npn.app.ui.themedScene
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.testing.FakeMetadataWriter
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The compact detail's document pager: settled selection and drafts committed when a page leaves. */
class DocumentPagerTest {
    private class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    @Test
    fun composedPageKeepsItsStoreUntilItLeavesEvenWhenRouteMovesOn() =
        onUiThread {
            val stores = KeyedViewModelStores<String>()
            val route = ViewModelStoreLease(stores)
            val panel = ViewModelStoreLease(stores)
            route.of("a")
            panel.of("a")
            var visible by mutableStateOf(true)
            lateinit var probe: Probe
            val scene =
                themedScene(WIDTH, HEIGHT) {
                    if (visible) {
                        val owner = rememberPageStore(stores, "a")
                        probe = viewModel(viewModelStoreOwner = owner) { Probe() }
                    }
                }
            scene.closing {
                render()
                route.of("b")
                route.keepOnly("b")
                panel.keepOnly(null)
                probe.cleared shouldBe false
                visible = false
                render()
                probe.cleared shouldBe true
                route.keepOnly(null)
            }
        }

    @Test
    fun delayedReviewCompletionDoesNotNavigateFromAnotherPage() =
        onUiThread {
            val completions = mutableMapOf<String, () -> Unit>()
            var navigations = 0
            val scene =
                themedScene(WIDTH, HEIGHT) {
                    DocumentPager(
                        order = DetailBrowseOrder(listOf("a", "b"), "a"),
                        modifier = Modifier.fillMaxSize(),
                    ) { _, id, _, _, _, guard ->
                        completions[id] = { guard { navigations++ } }
                        Text(id)
                    }
                }
            scene.closing {
                settle()
                val completion = completions.getValue("a")
                drag(by = Offset(-320f, 0f))
                settle()
                completion()
                navigations shouldBe 0
                completions.getValue("b")()
                navigations shouldBe 1
            }
        }

    @Test
    fun swipingReportsTheSettledDocumentAndStopsAtTheEnds() =
        onUiThread {
            val shown = mutableListOf<String>()
            var lastNext: (() -> Unit)? = null
            val scene =
                themedScene(WIDTH, HEIGHT) {
                    DocumentPager(
                        order = DetailBrowseOrder(listOf("a", "b", "c"), "a"),
                        onShown = { shown += it },
                        modifier = Modifier.fillMaxSize(),
                    ) { _, docId, _, _, onNext, _ ->
                        lastNext = onNext
                        Text(docId)
                    }
                }
            scene.closing {
                settle()
                shown.last() shouldBe "a"

                drag(by = Offset(-320f, 0f))
                settle()
                shown.last() shouldBe "b"

                drag(by = Offset(-320f, 0f))
                settle()
                shown.last() shouldBe "c"
                lastNext shouldBe null

                // At the last page a further swipe stays there.
                drag(by = Offset(-320f, 0f))
                settle()
                shown.last() shouldBe "c"
            }
        }

    /**
     * Swiping to the next document leaves the old editor, and leaving it saves its draft: a typed
     * value is not lost by paging, nor written twice.
     */
    @Test
    fun leavingAPageThroughASwipeCommitsItsDraft() =
        onUiThread {
            val writer = FakeMetadataWriter()
            val store = DocumentDrafts(writer, CoroutineScope(Dispatchers.Unconfined))
            val scene =
                hostedScene(WIDTH, HEIGHT, LocalDocumentDrafts provides store) {
                    DocumentPager(order = DetailBrowseOrder(listOf("a", "b"), "a")) { _, docId, _, _, _, _ ->
                        DocumentEditor(
                            state = stateFor(docId),
                            writes = NoWrites,
                            preview = { Text("Preview $docId", modifier = it) },
                            onReviewNext = {},
                            hasNext = false,
                        )
                    }
                }
            scene.closing {
                settle()
                click { "Details" in it.texts() }
                settle()
                val title =
                    nodes().firstOrNull {
                        it.editableText() == "Electricity invoice a"
                    }
                assertNotNull(title)
                title.config[SemanticsActions.SetText].action?.invoke(AnnotatedString("Changed title"))
                settle()

                drag(by = Offset(-320f, 0f))
                settle()

                assertTrue(
                    writer.batchedEdits.any { edit ->
                        edit.docIds == listOf("a") && edit.fields[FieldKeys.TITLE] == "Changed title"
                    },
                    "the draft of the page swiped away was not written: ${writer.batchedEdits}",
                )
            }
        }

    private fun stateFor(docId: String): MetadataUiState =
        MetadataUiState(
            document =
                DocumentSection(
                    loadState =
                        DocLoadState.Present(
                            listItem(doc(docId, title = "Electricity invoice $docId"), listOf("Utilities")),
                        ),
                    reviewMode = ReviewMode.INBOX_WORKFLOW,
                    fieldDefs =
                        FieldKeys.seedDefinitions { FieldKeys.SEED_LABELS.getValue(it) },
                ),
        )

    private var clock = 0L

    /** One finger pressed at the middle of the screen and moved horizontally by [by]. */
    private fun ImageComposeScene.drag(by: Offset) {
        val from = Offset(WIDTH / 2f, HEIGHT / 2f)

        fun touch(
            type: PointerEventType,
            at: Offset,
            pressed: Boolean,
        ) = sendPointerEvent(type, listOf(ComposeScenePointer(PointerId(0), at, pressed, PointerType.Touch)))

        touch(PointerEventType.Press, from, pressed = true)
        for (step in 1..DRAG_STEPS) {
            touch(PointerEventType.Move, from + by * (step.toFloat() / DRAG_STEPS), pressed = true)
            clock += FRAME_NANOS
            render(clock)
        }
        touch(PointerEventType.Release, from + by, pressed = false)
    }

    /** Render frames a frame apart until well past a pager's settle animation. */
    private fun ImageComposeScene.settle() {
        repeat(FRAMES) {
            clock += FRAME_NANOS
            render(clock)
        }
    }

    private companion object {
        const val WIDTH = 360
        const val HEIGHT = 740
        const val FRAMES = 90
        const val FRAME_NANOS = 16_000_000L
        const val DRAG_STEPS = 20
    }
}
