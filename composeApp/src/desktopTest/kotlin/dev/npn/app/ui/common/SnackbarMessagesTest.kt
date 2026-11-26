@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.ImageComposeScene
import dev.npn.app.ui.closing
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.text
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.test.Test

/**
 * [SnackbarMessages] against a real composition. No SnackbarHost is composed, so nothing times a
 * snackbar out: each one stays until the test dismisses it or taps its action.
 */
class SnackbarMessagesTest {
    @Test
    fun twoMessagesSentTogetherBothShowOneAfterTheOther() =
        onUiThread {
            val snackbar = SnackbarHostState()
            withScene(flowOf(text("first"), text("second")), snackbar) { scene ->
                snackbar.shownMessage() shouldBe "first"

                snackbar.currentSnackbarData?.dismiss()
                scene.render()

                snackbar.shownMessage() shouldBe "second"

                snackbar.currentSnackbarData?.dismiss()
                scene.render()

                snackbar.shownMessage() shouldBe null
            }
        }

    /** A message's action is offered and runs when tapped; the latest action is the one kept. */
    @Test
    fun aMessagesActionIsOfferedAndRunsWhenTapped() =
        onUiThread {
            val snackbar = SnackbarHostState()
            var undone = 0
            val undo =
                UiMessage(
                    UiText.Raw("Moved 2 documents to trash"),
                    UiMessage.Action(UiText.Raw("Undo")) { undone++ },
                )
            withScene(flowOf(undo), snackbar) { scene ->
                snackbar.currentSnackbarData
                    ?.visuals
                    ?.actionLabel shouldBe "Undo"

                snackbar.currentSnackbarData?.performAction()
                scene.render()

                undone shouldBe 1
                snackbar.shownMessage() shouldBe null
            }
        }

    /** A newer Undo replaces the pending one, so the bar never offers an action on an older write. */
    @Test
    fun aNewestUndoReplacesThePendingOne() =
        onUiThread {
            val snackbar = SnackbarHostState()
            var firstUndone = 0
            var secondUndone = 0
            val sink = MessageSink()
            sink.message(UiText.Raw("Trashed 2 documents"), undo { firstUndone++ })
            sink.message(UiText.Raw("Trashed 5 documents"), undo { secondUndone++ })
            withScene(sink.messages, snackbar) { scene ->
                snackbar.shownMessage() shouldBe "Trashed 5 documents"

                snackbar.currentSnackbarData?.visuals?.actionLabel shouldBe "Undo"
                snackbar.currentSnackbarData?.performAction()
                scene.render()

                firstUndone shouldBe 0
                secondUndone shouldBe 1
            }
        }

    @Test
    fun newerUndoReplacesOlderEvenBehindPlainMessage() =
        onUiThread {
            val snackbar = SnackbarHostState()
            var first = 0
            var second = 0
            val sink = MessageSink()
            sink.message(UiText.Raw("first"), undo { first++ })
            withScene(sink.messages, snackbar) { scene ->
                snackbar.shownMessage() shouldBe "first"
                sink.message(UiText.Raw("failure"))
                scene.render()
                sink.message(UiText.Raw("second"), undo { second++ })
                scene.render()
                snackbar.shownMessage() shouldBe "failure"
                snackbar.currentSnackbarData?.dismiss()
                scene.render()
                snackbar.shownMessage() shouldBe "second"
                snackbar.currentSnackbarData?.performAction()
                scene.render()
                first shouldBe 0
                second shouldBe 1
            }
        }

    /** Only an Undo replaces one: an import's Show waits its turn rather than wiping a pending Undo. */
    @Test
    fun anActionThatIsNotUndoQueuesBehindAnUndo() =
        onUiThread {
            val snackbar = SnackbarHostState()
            var undone = 0
            val sink = MessageSink()
            sink.message(UiText.Raw("Trashed 2 documents"), undo { undone++ })
            withScene(sink.messages, snackbar) { scene ->
                sink.message(UiText.Raw("Imported 3 files"), UiMessage.Action(UiText.Raw("Show")) {})
                scene.render()
                snackbar.shownMessage() shouldBe "Trashed 2 documents"
                snackbar.currentSnackbarData?.performAction()
                scene.render()
                undone shouldBe 1
                scene.render()
                snackbar.shownMessage() shouldBe "Imported 3 files"
            }
        }

    /**
     * Messages are taken one at a time, as each gets its turn: the ones still waiting when the screen
     * goes stay with the viewmodel for the next screen to show, rather than being lost with this one.
     */
    @Test
    fun messagesNotShownYetStayQueuedWhenTheScreenCloses() =
        onUiThread {
            val snackbar = SnackbarHostState()
            val messages = Channel<UiMessage>(Channel.UNLIMITED)
            listOf("first", "second", "third").forEach { messages.trySend(text(it)) }
            withScene(messages.receiveAsFlow(), snackbar) {
                snackbar.shownMessage() shouldBe "first"
            }
            generateSequence { messages.tryReceive().getOrNull()?.text }.toList() shouldBe
                listOf(UiText.Raw("second"), UiText.Raw("third"))
        }

    private fun text(value: String) = UiMessage(UiText.Raw(value))

    private fun undo(onUndo: () -> Unit) = UiMessage.Action(UiText.Raw("Undo"), undo = true) { onUndo() }

    private fun SnackbarHostState.shownMessage(): String? = currentSnackbarData?.visuals?.message

    private fun withScene(
        messages: Flow<UiMessage>,
        snackbar: SnackbarHostState,
        block: (ImageComposeScene) -> Unit,
    ) {
        val scene = ImageComposeScene(WIDTH, HEIGHT) { SnackbarMessages(messages, snackbar) }
        scene.closing {
            // One frame starts the collector, the next composes the first message's snackbar.
            render()
            render()
            block(this)
        }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 200
    }
}
