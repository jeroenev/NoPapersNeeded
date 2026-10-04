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

    companion object {
        /**
         * How long one failed endpoint call parks every queue that calls the endpoint ([EndpointHealth]),
         * whichever row failed. A short constant rather than the failed row's own backoff, because a
         * failure cannot tell an outage from one row the endpoint always rejects: charging every
         * later row the poison row's escalating delay would let it stall the queues for a day, while
         * a flat minute makes the worst case of guessing wrong a minute of latency.
         */
        const val ENDPOINT_COOLDOWN_MS = 60L * 1000L
    }
}

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
    private val cooldownMs: Long = QueueRetryPolicy.ENDPOINT_COOLDOWN_MS,
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
}
