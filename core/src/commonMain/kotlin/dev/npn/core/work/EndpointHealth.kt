package dev.npn.core.work

import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Whether an endpoint is worth calling right now. One failed call parks every queue that shares it
 * for [cooldownMs]; any answer lifts that at once. Kept in memory: it is a statement about the
 * endpoint rather than about any row, and an app restart is the one moment where trying again
 * immediately is most likely to be right.
 *
 * Shared, so the queues behind one endpoint do not each probe it while it is down: a failure in one
 * holds the others back too.
 */
internal class EndpointHealth(
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
