@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.ImageComposeScene
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.swing.SwingUtilities
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
            withScene(flowOf(undo, undo), snackbar) { scene ->
                snackbar.currentSnackbarData
                    ?.visuals
                    ?.actionLabel shouldBe "Undo"

                snackbar.currentSnackbarData?.performAction()
                scene.render()

                undone shouldBe 1
                // An identical message right behind it still shows.
                snackbar.shownMessage() shouldBe "Moved 2 documents to trash"

                snackbar.currentSnackbarData?.dismiss()
                scene.render()

                undone shouldBe 1
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
        try {
            // One frame starts the collector, the next composes the first message's snackbar.
            scene.render()
            scene.render()
            block(scene)
        } finally {
            scene.close()
        }
    }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 200
    }
}
