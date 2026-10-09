package dev.npn.desktop

import dev.npn.core.util.NpnLog
import dev.npn.core.util.runLogged
import dev.npn.core.util.warn
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration

/**
 * Closing the window: [close] the library, then [exit], whatever the close did. A close that fails
 * or outlasts [timeout] is logged, not thrown: the window is already hidden, so a process that
 * stayed would be invisible and keep the single-instance lock, turning every later launch away.
 */
internal suspend fun closeThenExit(
    close: suspend () -> Unit,
    timeout: Duration,
    log: NpnLog,
    exit: () -> Unit,
) {
    try {
        log.runLogged("Closing the library") {
            withTimeoutOrNull(timeout) { close() } ?: log.warn("Closing the library took over $timeout; exiting anyway")
        }
    } finally {
        exit()
    }
}
