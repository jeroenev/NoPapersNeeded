package dev.npn.core.sync

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TransferEachTest {
    @Test
    fun transfersRunUpToTheLimitAtOnceAndSettleOneAtATime() =
        runTest {
            var running = 0
            var mostRunning = 0
            var settling = 0
            var mostSettling = 0
            val settled = ArrayList<Pair<Int, Result<Int>>>()
            transferEach(
                (1..10).toList(),
                concurrency = 3,
                transfer = { n ->
                    running++
                    mostRunning = maxOf(mostRunning, running)
                    delay(100L * (n % 4))
                    running--
                    if (n == 5) error("transfer $n failed")
                    n * 10
                },
            ) { n, outcome ->
                settling++
                mostSettling = maxOf(mostSettling, settling)
                delay(10)
                settled += n to outcome
                settling--
            }
            mostRunning shouldBe 3
            mostSettling shouldBe 1
            settled.map { it.first } shouldContainExactlyInAnyOrder (1..10).toList()
            // A failure is that item's outcome, never the others'.
            settled.filter { it.second.isFailure }.map { it.first } shouldBe listOf(5)
            settled.filter { it.second.isSuccess }.forEach { (n, outcome) -> outcome.getOrThrow() shouldBe n * 10 }
        }
}
