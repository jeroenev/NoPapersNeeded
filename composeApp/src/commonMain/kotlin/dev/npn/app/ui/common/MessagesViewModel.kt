package dev.npn.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource

/**
 * Base for view models that surface one-shot user-facing messages, shown by [SnackbarMessages].
 *
 * Messages go through one unbounded channel: each is delivered exactly once, in the order sent, to
 * the one collector the screen runs. A message sent while no screen collects (between lifecycles,
 * during navigation) waits for the next collector instead of being dropped, however many pile up,
 * and two sent together both show, one after the other.
 *
 * Write operations are journal appends and file moves, so they can genuinely fail (SAF I/O errors,
 * full disk, permissions). Fire-and-forget writes must go through [launchReporting] so a failure
 * is reported to the user instead of silently swallowed, or crashing the app.
 */
abstract class MessagesViewModel : ViewModel() {
    private val channel = Channel<UiMessage>(Channel.UNLIMITED)

    /** The messages to show; collect it from one place only, normally [SnackbarMessages]. */
    val messages: Flow<UiMessage> = channel.receiveAsFlow()

    /** Send a one-shot user-facing message, optionally with an [action] such as Undo. */
    protected fun message(
        text: UiText,
        action: UiMessage.Action? = null,
    ) {
        channel.trySend(UiMessage(text, action))
    }

    /**
     * Run a fire-and-forget write in [viewModelScope]; on failure send [failed], a whole sentence
     * with the failure's reason as `%1$s`, such as "Could not split the document: %1$s".
     * Cancellation is rethrown so it is not misreported as a failure.
     */
    protected fun launchReporting(
        failed: StringResource,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch { reporting(failed, block) }
    }

    /**
     * [launchReporting]'s failure handling, for a write inside a coroutine the caller launched.
     * Returns whether [block] completed, for a caller that keeps what it wrote until it has landed.
     */
    protected suspend fun reporting(
        failed: StringResource,
        block: suspend () -> Unit,
    ): Boolean =
        runCatchingCancellable { block() }
            .onFailure { message(uiText(failed, it.reasonText())) }
            .isSuccess
}
