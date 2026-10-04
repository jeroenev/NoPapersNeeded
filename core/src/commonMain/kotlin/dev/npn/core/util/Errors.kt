package dev.npn.core.util

import kotlin.coroutines.cancellation.CancellationException

/**
 * [runCatching] for code that may suspend: a [CancellationException] is rethrown rather than
 * captured. Captured, it would stop the cancellation of the coroutine running [block] — a closing
 * session, a left screen — and report it as an ordinary failure instead.
 *
 * Inline, so [block] may call suspend functions when the caller is a suspend function.
 */
inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (t: Throwable) {
        Result.failure(t)
    }

/**
 * A short, human-readable account of this failure for a log line or a stored diagnostic: its
 * message, or its class name when it has none. Never blank.
 */
fun Throwable.reason(): String = message?.takeIf { it.isNotBlank() } ?: this::class.simpleName ?: "Unknown error"
