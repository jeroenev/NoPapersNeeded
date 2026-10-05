package dev.npn.app.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.flow.Flow
import org.jetbrains.compose.resources.rememberResourceEnvironment

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
    // The composition's resource environment, so the text follows the app's locale override.
    val environment by rememberUpdatedState(rememberResourceEnvironment())
    LaunchedEffect(messages) {
        messages.collect { message ->
            val text = message.text.resolve(environment)
            val label = message.action?.label?.resolve(environment)
            val result = snackbar.showSnackbar(text, label, withDismissAction = label != null)
            if (result == SnackbarResult.ActionPerformed) message.action?.onPerform()
        }
    }
}
