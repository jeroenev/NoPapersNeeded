package dev.npn.core.work

/**
 * When a persisted queue tries a failed row again, and when it gives up on it. Every queue that
 * retries (OCR, automatic enrichment, queued edit runs) holds one, so a failure costs the same wait
 * everywhere: the [backoffMs] ramp after each failure, and with [maxAttempts] a cap at which the
 * row is given up on.
 */
internal class QueueRetryPolicy(
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
internal fun backoffMs(attempts: Int): Long =
    (BACKOFF_BASE_MS shl (attempts - 1).coerceIn(0, BACKOFF_MAX_SHIFT))
        .coerceAtMost(BACKOFF_CEILING_MS)
