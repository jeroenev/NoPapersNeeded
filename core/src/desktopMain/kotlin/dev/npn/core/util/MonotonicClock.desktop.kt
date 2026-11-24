package dev.npn.core.util

/**
 * The desktop's elapsed-time source. It is the wall clock, because Linux's and Java's monotonic
 * clocks stop while the machine is suspended (the lid closed), which is exactly when a lock must
 * count the time away. [dev.npn.core.security.AppLock] clamps a backwards jump (locks as if the
 * whole timeout had passed) and caps a forward one at the longest backoff, so a clock change can
 * only make it lock sooner.
 */
@Suppress("ForbiddenMethodCall") // The one desktop time read, behind SystemMonotonicClock.
actual fun monotonicNowMillis(): Long = System.currentTimeMillis()
