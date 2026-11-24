package dev.npn.core.util

/**
 * Monotonic elapsed time in milliseconds: for measuring intervals (how long the app was in the
 * background, how long until a retry), never for naming an instant. A wall clock can jump backwards
 * or forwards (an NTP correction, a user changing the device time), which would otherwise shorten
 * or bypass a security timeout; this one only moves forward while the device runs.
 *
 * The app lock uses it, so tests inject a fake and production never reads the wall clock for a
 * duration.
 */
fun interface MonotonicClock {
    fun elapsedMillis(): Long
}

/** Platform monotonic clock, backed by [monotonicNowMillis]. */
val SystemMonotonicClock: MonotonicClock = MonotonicClock { monotonicNowMillis() }

/** Milliseconds since an arbitrary fixed point (boot, or process start where no boot clock exists). */
expect fun monotonicNowMillis(): Long
