package dev.npn.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.ResourceEnvironment
import org.jetbrains.compose.resources.rememberResourceEnvironment

/**
 * Shows each of [messages] as a snackbar on [snackbar], one at a time and in order. The one
 * collector of a [MessagesViewModel.messages]; a screen calls it once per viewmodel.
 *
 * It takes a message from the viewmodel only when the one before has left the screen, so the ones
 * still waiting when the screen goes stay with the viewmodel for the next collector, and each is
 * resolved only when its turn comes. The one exception to waiting is an Undo
 * ([UiMessage.Action.undo]): one a newer Undo superseded is taken off screen at once, or skipped if
 * it has not shown yet, so an Undo always acts on the latest write. A message's action carries its
 * own [SnackbarDuration], so an Undo does not hold the queue indefinitely.
 */
@Composable
fun SnackbarMessages(
    messages: Flow<UiMessage>,
    snackbar: SnackbarHostState,
) {
    // The composition's resource environment, so the text follows the app's locale override.
    val environment by rememberUpdatedState(rememberResourceEnvironment())
    LaunchedEffect(messages, snackbar) {
        messages.collect { message ->
            val action = message.action
            if (action?.superseded?.value == true) return@collect
            coroutineScope {
                val shown = launch { snackbar.present(message, environment) }
                // Cancelling the call dismisses its snackbar: a host clears its data when it ends.
                val superseded =
                    action?.let {
                        launch {
                            it.superseded.first { gone -> gone }
                            shown.cancel()
                        }
                    }
                shown.join()
                superseded?.cancel()
            }
        }
    }
}

private suspend fun SnackbarHostState.present(
    message: UiMessage,
    env: ResourceEnvironment,
) {
    val text = message.text.resolve(env)
    val label = message.action?.label?.resolve(env)
    val result =
        showSnackbar(
            message = text,
            actionLabel = label,
            withDismissAction = label != null,
            duration = message.action?.duration ?: SnackbarDuration.Short,
        )
    if (result == SnackbarResult.ActionPerformed) message.action?.onPerform()
}

/**
 * A snackbar host state showing [messages] ([SnackbarMessages]): what a screen with one viewmodel
 * and its own snackbar host needs.
 */
@Composable
fun rememberSnackbarMessages(messages: Flow<UiMessage>): SnackbarHostState {
    val snackbar = remember { SnackbarHostState() }
    SnackbarMessages(messages, snackbar)
    return snackbar
}
