package dev.npn.core.work

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class QueueRetryPolicyTest {
    @Test
    fun aRowWaitsOutTheSharedRampAfterEachFailure() {
        val policy = QueueRetryPolicy()

        policy.nextAttemptAt(now = 1_000L, attempts = 1) shouldBe 1_000L + backoffMs(1)
        policy.nextAttemptAt(now = 1_000L, attempts = 4) shouldBe 1_000L + backoffMs(4)
    }

    @Test
    fun aCappedPolicyGivesUpAtItsCapAndAnUncappedOneNever() {
        val capped = QueueRetryPolicy(maxAttempts = 3)

        capped.givesUp(2) shouldBe false
        capped.givesUp(3) shouldBe true
        QueueRetryPolicy().givesUp(Int.MAX_VALUE) shouldBe false
    }

    @Test
    fun aFailureCoolsTheEndpointDownUntilTheCooldownPassesOrItAnswers() {
        val health = EndpointHealth(cooldownMs = 100L)
        health.coolingDown(now = 0L) shouldBe false

        health.failed(now = 1_000L)
        health.coolingDown(now = 1_099L) shouldBe true
        health.coolingDown(now = 1_100L) shouldBe false

        health.failed(now = 2_000L)
        health.answered()
        health.coolingDown(now = 2_001L) shouldBe false
    }
}
