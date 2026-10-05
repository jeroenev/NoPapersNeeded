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
        val allocator = HlcWatermarkAllocator({ 1000L })
        val first = allocator.next("dev")
        val second = allocator.next("dev") // same wall time -> counter bumps
        assertEquals(first.physicalMillis, second.physicalMillis)
        assertEquals(first.counter + 1, second.counter)
        assertTrue(second > first)
    }

    @Test
    fun next_uses_wall_when_it_advances() {
        var now = 1000L
        val allocator = HlcWatermarkAllocator({ now })
        allocator.next("dev")
        now = 2000L
        val second = allocator.next("dev")
        assertEquals(2000L, second.physicalMillis)
        assertEquals(0, second.counter)
    }

    @Test
    fun next_does_not_rewind_on_backwards_wall_clock() {
        var now = 5000L
        val allocator = HlcWatermarkAllocator({ now })
        val first = allocator.next("dev")
        now = 1000L // clock went backwards
        val second = allocator.next("dev")
        assertEquals(first.physicalMillis, second.physicalMillis)
        assertTrue(second > first)
    }

    @Test
    fun observe_merges_remote_ahead() {
        val allocator = HlcWatermarkAllocator({ 1000L })
        allocator.next("local")
        val remote = Hlc(3000, 5, "remote")
        allocator.observe(remote)
        assertEquals(3000L, allocator.physicalMillis)
        assertTrue(allocator.counter > 5, "counter must advance beyond remote at same physical")
        assertTrue(allocator.next("local") > remote)
    }

    @Test
    fun observe_clamps_absurd_remote_future() {
        val now = 1_000_000L
        val allocator = HlcWatermarkAllocator({ now })
        allocator.observe(Hlc(now + HlcWatermarkAllocator.CLAMP_MS * 10, 0, "bad"))
        assertTrue(
            allocator.physicalMillis <= now + HlcWatermarkAllocator.CLAMP_MS + 1,
            "remote clamped to local wall + CLAMP_MS, was ${allocator.physicalMillis}",
        )
    }

    @Test
    fun observe_uses_local_wall_when_remote_behind() {
        val now = 10_000L
        val allocator = HlcWatermarkAllocator({ now })
        allocator.observe(Hlc(500, 0, "remote"))
        assertEquals(now, allocator.physicalMillis)
        assertEquals(0, allocator.counter)
    }

    @Test
    fun transaction_actor_allocator_stays_monotonic_across_actor_changes() {
        val allocator = HlcWatermarkAllocator(clock = { 1000L })
        val first = allocator.next("00000000-0000-4000-8000-000000000001")
        val second = allocator.next("00000000-0000-4000-8000-000000000002")
        assertTrue(second > first)
        assertEquals(first.counter + 1, second.counter)
    }
}
