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

            fun undo(
                message: String,
                onUndo: () -> Unit,
            ) = UiMessage(
                UiText.Raw(message),
                UiMessage.Action(UiText.Raw("Undo")) { onUndo() },
            )
            withScene(
                flowOf(undo("Trashed 2 documents") { firstUndone++ }, undo("Trashed 5 documents") { secondUndone++ }),
                snackbar,
            ) { scene ->
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

            fun undo(
                label: String,
                action: () -> Unit,
            ) = UiMessage(UiText.Raw(label), UiMessage.Action(UiText.Raw("Undo")) { action() })
            val messages = Channel<UiMessage>(Channel.UNLIMITED)
            messages.trySend(undo("first") { first++ })
            withScene(messages.receiveAsFlow(), snackbar) { scene ->
                snackbar.shownMessage() shouldBe "first"
                messages.trySend(text("failure"))
                scene.render()
                messages.trySend(undo("second") { second++ })
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

    private fun text(value: String) = UiMessage(UiText.Raw(value))

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
