package dev.npn.core.util

/**
 * Wall-clock source in epoch milliseconds: the app-wide clock. Code that reads the time takes one,
 * so a test controls it; production passes [SystemEpochClock].
 */
fun interface EpochClock {
    fun nowMillis(): Long
}

/** Platform wall clock, backed by [systemNowMillis]. */
val SystemEpochClock: EpochClock = EpochClock { systemNowMillis() }

/** Platform wall clock in epoch milliseconds (actual per target). */
expect fun systemNowMillis(): Long
