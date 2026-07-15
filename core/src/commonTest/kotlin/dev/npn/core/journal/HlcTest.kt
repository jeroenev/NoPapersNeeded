package dev.npn.core.journal

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HlcTest {

    @Test
    fun total_order_physical_then_counter_then_device() {
        val a = Hlc(100, 0, "z")
        val b = Hlc(100, 0, "a")
        assertTrue(a > b, "device id breaks ties, z > a")

        val c = Hlc(100, 1, "a")
        assertTrue(c > b, "counter beats device tiebreak")

        val d = Hlc(101, 0, "a")
        assertTrue(d > c, "physical beats counter")
    }

    @Test
    fun equal_when_all_equal() {
        assertEquals(0, Hlc(5, 2, "x").compareTo(Hlc(5, 2, "x")))
    }

    @Test
    fun next_monotonic_when_wall_stalls() {
        var now = 1000L
        val clock = HlcClock("dev", { now })
        val first = clock.next()
        val second = clock.next() // same wall time -> counter bumps
        assertEquals(first.physicalMillis, second.physicalMillis)
        assertEquals(first.counter + 1, second.counter)
        assertTrue(second > first)
    }

    @Test
    fun next_uses_wall_when_it_advances() {
        var now = 1000L
        val clock = HlcClock("dev", { now })
        clock.next()
        now = 2000L
        val second = clock.next()
        assertEquals(2000L, second.physicalMillis)
        assertEquals(0, second.counter)
    }

    @Test
    fun next_does_not_rewind_on_backwards_wall_clock() {
        var now = 5000L
        val clock = HlcClock("dev", { now })
        val first = clock.next()
        now = 1000L // clock went backwards
        val second = clock.next()
        assertEquals(first.physicalMillis, second.physicalMillis)
        assertTrue(second > first)
    }

    @Test
    fun observe_merges_remote_ahead() {
        var now = 1000L
        val clock = HlcClock("local", { now })
        clock.next()
        val remote = Hlc(3000, 5, "remote")
        val merged = clock.observe(remote)
        assertEquals(3000L, merged.physicalMillis)
        assertTrue(merged.counter > 5, "counter must advance beyond remote at same physical")
        assertEquals("local", merged.deviceId)
        assertTrue(merged > remote)
    }

    @Test
    fun observe_clamps_absurd_remote_future() {
        val now = 1_000_000L
        val clock = HlcClock("local", { now })
        val absurd = Hlc(now + HlcClock.CLAMP_MS * 10, 0, "bad")
        val merged = clock.observe(absurd)
        assertTrue(
            merged.physicalMillis <= now + HlcClock.CLAMP_MS + 1,
            "remote clamped to local wall + CLAMP_MS, was ${merged.physicalMillis}",
        )
    }

    @Test
    fun observe_uses_local_wall_when_remote_behind() {
        val now = 10_000L
        val clock = HlcClock("local", { now })
        val remote = Hlc(500, 0, "remote")
        val merged = clock.observe(remote)
        assertEquals(now, merged.physicalMillis)
        assertEquals(0, merged.counter)
    }
}
