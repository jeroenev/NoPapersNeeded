package dev.npn.core.ai

import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.CancellationException

/**
 * Ask [attempt] for one page's text up to [attempts] times, and turn what comes back into the
 * [AiFacadeImpl.ocrEngine] contract. Separated from the endpoint call it wraps because this is the
 * part that decides how many paid calls a bad page costs, and that decision is worth testing
 * without a live model behind it.
 *
 * [attempt] reports three things, and each means something different here:
 *  - a string (including `""` for a page the model called blank) — an answer; stop.
 *  - `null` — nothing usable came back; ask again while attempts remain, then give up and return
 *    `""`, which is the same "no text on this page" a local engine reports for a page it cannot
 *    read.
 *  - a throw — the endpoint failed. Retry, but if every attempt threw, rethrow the last failure:
 *    the page's text is unknown, and publishing a write-once sidecar with a silent hole in it is
 *    worse than failing the run.
 *
 * [CancellationException] is never a failure to retry. It means the caller is gone, so it leaves
 * immediately rather than spending another call on a result nobody will read.
 */
internal suspend fun transcribeWithRetry(
    attempts: Int,
    attempt: suspend () -> String?,
): String {
    var failure: Throwable? = null
    repeat(attempts) {
        val outcome = runCatchingCancellable { attempt() }
        outcome.getOrNull()?.let { text -> return text }
        failure = outcome.exceptionOrNull() ?: failure
    }
    failure?.let { throw it }
    return ""
}
