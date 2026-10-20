// checkAll's per-test PropTestConfig overload (iteration counts here are tuned per property) is
// still marked experimental by kotest.
@file:OptIn(ExperimentalKotest::class)

package dev.npn.core.journal

import io.kotest.common.ExperimentalKotest
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlin.test.Test

class HlcAllocatorPropertyTest {
    /** One step: the wall clock reads [wall], then the allocator either writes or observes [remote]. */
    private data class Step(
        val wall: Long,
        val remote: Hlc?,
    )

    // Small ranges, so physical times collide and the counter decides the order.
    private val steps =
        Arb.list(
            Arb.bind(Arb.long(0L, 5L), Arb.boolean(), Arb.long(0L, 5L), Arb.int(0, 8)) { wall, local, t, c ->
                Step(wall, if (local) null else Hlc(t, c, DEVICE))
            },
            1..30,
        )

    /**
     * Whatever the wall clock does, every HLC the allocator hands out is after every one it handed out
     * or observed before: an edit made after another device's is ordered after it, at equal physical
     * times too. One device id throughout, so only the time and the counter order them.
     */
    @Test
    fun everyNextIsAfterEverythingProducedOrObservedBefore() =
        propTest {
            checkAll(PropTestConfig(iterations = 1_000), steps) { run ->
                var wall = 0L
                val allocator = HlcWatermarkAllocator({ wall })
                var latest: Hlc? = null
                for (step in run) {
                    wall = step.wall
                    val seen = step.remote?.also(allocator::observe) ?: allocator.next(DEVICE)
                    if (step.remote == null) {
                        require(latest == null || seen > latest) { "$seen is not after $latest in $run" }
                    }
                    latest = if (latest == null || seen > latest) seen else latest
                }
            }
        }

    private companion object {
        const val DEVICE = "d"
    }
}
