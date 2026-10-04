package dev.npn.app.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow

/**
 * Shows each of [messages] as a snackbar on [snackbar], one at a time and in order. The one
 * collector of a [MessagesViewModel.messages]; a screen calls it once per viewmodel.
 *
 * A message is taken from the flow only once the previous snackbar is gone, so messages sent
 * together queue up rather than replace each other, and one not yet taken stays with its viewmodel
 * for the next collector. A message's [UiMessage.Action] becomes the snackbar's action button.
 */
@Composable
fun SnackbarMessages(
    messages: Flow<UiMessage>,
    snackbar: SnackbarHostState,
) {
    var showing by remember { mutableStateOf<Showing?>(null) }
    LaunchedEffect(messages) {
        messages.collect { message ->
            val next = Showing(message)
            showing = next
            try {
                next.shown.await()
            } finally {
                showing = null
            }
        }
    }
    // Resolved here, in composition, so the text follows the app's locale override.
    val current = showing ?: return
    val text = current.message.text.resolve()
    val actionLabel =
        current.message.action
            ?.label
            ?.resolve()
    LaunchedEffect(current) {
        val result = snackbar.showSnackbar(text, actionLabel, withDismissAction = actionLabel != null)
        if (result == SnackbarResult.ActionPerformed) current.message.action?.onPerform()
        current.shown.complete(Unit)
    }
}

/** One message on its way to the screen; a fresh instance per message, so an equal one re-shows. */
private class Showing(
    val message: UiMessage,
) {
    val shown = CompletableDeferred<Unit>()
}
