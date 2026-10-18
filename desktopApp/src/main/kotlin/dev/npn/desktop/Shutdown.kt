package dev.npn.desktop

import dev.npn.core.util.NpnLog
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Closing the window: [close] the library, then [exit], whatever the close did. A close that fails
 * or outlasts [timeout] is logged, not thrown: the window is already hidden, so a process that
 * stayed would be invisible and keep the single-instance lock, turning every later launch away.
 *
 * The close runs as its own job and only the wait for it is bounded: closing a session is not
 * cancellable (it always finishes once begun), so a timeout around the close itself would wait it
 * out anyway. One still running at the timeout is left to the exit.
 */
internal suspend fun closeThenExit(
    close: suspend () -> Unit,
    timeout: Duration,
    log: NpnLog,
    exit: () -> Unit,
) {
    try {
        // Not a child of the caller, so neither waiting nor the caller's own end depends on it.
        val closing = CoroutineScope(currentCoroutineContext().minusKey(Job)).async { close() }
        log.runLogged("Closing the library") {
            withTimeoutOrNull(timeout) { closing.await() }
                ?: log.warn("Closing the library took over $timeout; exiting anyway")
        }
        closing.cancel()
    } finally {
        exit()
    }
}
