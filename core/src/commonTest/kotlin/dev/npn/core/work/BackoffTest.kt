package dev.npn.core.work

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class BackoffTest {
    /** The ceiling is what makes retrying forever affordable: at most one attempt a day per row. */
    @Test
    fun theBackoffDoublesUpToADailyCeilingAndHoldsThere() {
        assertEquals(1.minutes.inWholeMilliseconds, backoffMs(1))
        assertEquals(2.minutes.inWholeMilliseconds, backoffMs(2))
        assertEquals(4.minutes.inWholeMilliseconds, backoffMs(3))
        assertEquals(128.minutes.inWholeMilliseconds, backoffMs(8))
        assertEquals(1024.minutes.inWholeMilliseconds, backoffMs(11))
        // 2048m would overshoot the ceiling, so this is where it settles.
        assertEquals(1.days.inWholeMilliseconds, backoffMs(12))
        assertEquals(1.days.inWholeMilliseconds, backoffMs(500))
    }
}
