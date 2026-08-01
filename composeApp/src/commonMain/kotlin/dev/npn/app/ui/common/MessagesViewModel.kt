package dev.npn.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Base for view models that surface one-shot user-facing messages as snackbars.
 *
 * Write operations are journal appends and file moves, so they can genuinely fail (SAF I/O errors,
 * full disk, permissions). Fire-and-forget writes must go through [launchReporting] so a failure
 * is reported to the user instead of silently swallowed — a failed edit looks identical to the
 * flaky-merge bugs we fixed otherwise.
 */
abstract class MessagesViewModel : ViewModel() {
    // extraBufferCapacity so tryEmit from viewModelScope never suspends or drops under bursts.
    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages.asSharedFlow()

    /** Emit a one-shot user-facing message (snackbar). */
    protected fun message(text: UiText) {
        _messages.tryEmit(UiMessage(text))
    }

    /**
     * Run a fire-and-forget write in [viewModelScope]; on failure emit "Could not [action] …" via
     * [messages]. Cancellation is rethrown so it is not misreported as a failure.
     */
    protected fun launchReporting(
        action: UiAction,
        block: suspend () -> Unit,
    ) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { e ->
                if (e is CancellationException) throw e
                message(uiText(UiTextKey.COULD_NOT_ACTION, action, reasonOf(e)))
            }
        }
    }

    /**
     * A short, user-facing reason for [e]: its message when present and non-blank, else its class
     * simple name, else a localized unknown-error fallback.
     */
    protected fun reasonOf(e: Throwable): UiText =
        e.message?.takeIf { it.isNotBlank() }?.let(UiText::Dynamic)
            ?: e::class.simpleName?.let(UiText::Dynamic)
            ?: uiText(UiTextKey.UNKNOWN_ERROR)
}
