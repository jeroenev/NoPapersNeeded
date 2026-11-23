package dev.npn.app.ui.common

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.rememberResourceEnvironment

/**
 * Shows each of [messages] as a snackbar on [snackbar], one at a time and in order. The one
 * collector of a [MessagesViewModel.messages]; a screen calls it once per viewmodel.
 *
 * Plain messages queue in order while the collector continues receiving newer actions. A message
 * with an [UiMessage.Action] is different: the newest one
 * replaces the one on screen when it arrives instead of queueing behind it, so an Undo always acts
 * on the latest write and never on the one before it. A message's action carries its own
 * [SnackbarDuration], so an Undo does not hold the queue indefinitely.
 */
@Composable
fun SnackbarMessages(
    messages: Flow<UiMessage>,
    snackbar: SnackbarHostState,
) {
    // The composition's resource environment, so the text follows the app's locale override.
    val environment by rememberUpdatedState(rememberResourceEnvironment())
    LaunchedEffect(messages) {
        coroutineScope {
            // The action message on screen, so a newer one can take its place (see the class docs).
            var actionJob: Job? = null
            messages.collect { message ->
                val env = environment

                suspend fun present() {
                    val text = message.text.resolve(env)
                    val label = message.action?.label?.resolve(env)
                    val result =
                        snackbar.showSnackbar(
                            message = text,
                            actionLabel = label,
                            withDismissAction = label != null,
                            duration = message.action?.duration ?: SnackbarDuration.Short,
                        )
                    if (result == SnackbarResult.ActionPerformed) message.action?.onPerform()
                }
                if (message.action == null) {
                    launch { present() }
                } else {
                    // Cancelling the showing one dismisses its snackbar (a host clears its data when
                    // the call ends), and the newest takes the mutex next.
                    actionJob?.cancel()
                    actionJob = launch { present() }
                }
            }
        }
    }
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
