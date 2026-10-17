package dev.npn.core.work

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * When a persisted queue tries a failed row again, and when it gives up on it. Every queue that
 * retries (OCR, automatic enrichment, queued edit runs) holds one, so a failure costs the same wait
 * everywhere: the [backoffMs] ramp after each failure, and with [maxAttempts] a cap at which the
 * row is given up on.
 */
class QueueRetryPolicy(
    /** Failed attempts after which a row is given up on; null retries for ever. */
    val maxAttempts: Int? = null,
) {
    /** When a row that has now failed [attempts] times may be tried again. */
    fun nextAttemptAt(
        now: Long,
        attempts: Int,
    ): Long = now + backoffMs(attempts)

    /** Whether a row that has now failed [attempts] times is given up on. */
    fun givesUp(attempts: Int): Boolean = maxAttempts != null && attempts >= maxAttempts
}

private const val BACKOFF_BASE_MS = 60L * 1000L
private const val BACKOFF_CEILING_MS = 24L * 60L * 60L * 1000L

/** Keeps the shift in range for an unbounded attempt count; well past the ceiling. */
private const val BACKOFF_MAX_SHIFT = 20

/**
 * How long a persisted queue's row waits after its [attempts]th failure before it may be tried
 * again: 1m, 2m, 4m, 8m … 17h, then a day forever. The ramp of every [QueueRetryPolicy], so a
 * failing endpoint is asked again on one schedule.
 *
 * Plain doubling rather than a coarser ramp: the interval is also how long a *recovered* endpoint
 * goes unnoticed, so overshooting costs latency on the common case (a blip, a tunnel, a laptop that
 * just woke) to save calls on the rare one. Eleven steps to the ceiling keeps every early retry
 * cheap while still settling at one call a day for a row that will never succeed.
 *
 * This is an earliest-retry time, not a schedule: nothing wakes a drain when a row falls due. A
 * retry is served by the next kick after that, so on an otherwise idle library the first few steps
 * all collapse onto the periodic maintenance tick and the observed ramp starts at that interval.
 * The doubling only separates the steps above it — which is where the calls being saved actually
 * are.
 */
fun backoffMs(attempts: Int): Long =
    (BACKOFF_BASE_MS shl (attempts - 1).coerceIn(0, BACKOFF_MAX_SHIFT))
        .coerceAtMost(BACKOFF_CEILING_MS)

/**
 * Whether an endpoint is worth calling right now. One failed call parks every queue that shares it
 * for [cooldownMs]; any answer lifts that at once. Kept in memory: it is a statement about the
 * endpoint rather than about any row, and an app restart is the one moment where trying again
 * immediately is most likely to be right.
 *
 * Shared, so the queues behind one endpoint do not each probe it while it is down: a failure in one
 * holds the others back too.
 */
class EndpointHealth(
    private val cooldownMs: Long = ENDPOINT_COOLDOWN_MS,
) {
    private val coolingUntil = MutableStateFlow(0L)

    /** Whether a call failed less than [cooldownMs] before [now] and nothing has answered since. */
    fun coolingDown(now: Long): Boolean = now < coolingUntil.value

    /** A call failed at [now]: hold every caller back until the cooldown has passed. */
    fun failed(now: Long) {
        coolingUntil.value = now + cooldownMs
    }

    /** A call was answered: the endpoint is up. */
    fun answered() {
        coolingUntil.value = 0L
    }

    companion object {
        /**
         * How long one failed endpoint call parks every queue that calls the endpoint, whichever row
         * failed. A short constant rather than the failed row's own backoff, because a failure
         * cannot tell an outage from one row the endpoint always rejects: charging every later row
         * the poison row's escalating delay would let it stall the queues for a day, while a flat
         * minute makes the worst case of guessing wrong a minute of latency.
         */
        const val ENDPOINT_COOLDOWN_MS = 60L * 1000L
    }
}
