package dev.npn.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Base for view models that surface one-shot user-facing messages. Screens collect [messages] and
 * show each as a snackbar:
 *
 * ```
 * LaunchedEffect(vm, snackbar) { vm.messages.collect { snackbar.showSnackbar(it) } }
 * ```
 *
 * Write operations are journal appends and file moves, so they can genuinely fail (SAF I/O errors,
 * full disk, permissions). Fire-and-forget writes must go through [launchReporting] so a failure
 * is reported to the user instead of silently swallowed — a failed edit looks identical to the
 * flaky-merge bugs we fixed otherwise.
 */
abstract class MessagesViewModel : ViewModel() {

    // extraBufferCapacity so tryEmit from viewModelScope never suspends or drops under bursts.
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Emit a one-shot user-facing message (snackbar). */
    protected fun message(text: String) {
        _messages.tryEmit(text)
    }

    /**
     * Run a fire-and-forget write in [viewModelScope]; on failure emit "Could not [action] …" via
     * [messages]. Cancellation is rethrown so it is not misreported as a failure.
     */
    protected fun launchReporting(action: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { e ->
                if (e is CancellationException) throw e
                message(couldNot(action, e))
            }
        }
    }

    private fun couldNot(action: String, e: Throwable): String {
        val reason = e.message?.takeIf { it.isNotBlank() } ?: (e::class.simpleName ?: "unknown error")
        return "Could not $action: $reason"
    }
}
