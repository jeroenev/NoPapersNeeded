package dev.npn.core.security

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** A phone's background is real: every interval is offered, and a new lock locks on every return. */
class AndroidLockTimeoutTest {
    private val secret = "correct horse"
    private val credential =
        LockCrypto.create(secret, iterations = LockCrypto.MIN_ITERATIONS, salt = ByteArray(LockCrypto.SALT_BYTES))

    @Test
    fun everyIntervalIsOfferedAndANewLockLocksImmediately() {
        LockTimeout.choices shouldBe LockTimeout.entries
        LockTimeout.DEFAULT shouldBe LockTimeout.IMMEDIATELY
        LockTimeout.fromWire(null) shouldBe LockTimeout.IMMEDIATELY
    }

    @Test
    fun immediateTimeoutLocksOnEveryReturn() {
        val clock = AppLockTest.FakeClock()
        val lock =
            AppLock(AppLockSettings(enabled = true, timeout = LockTimeout.IMMEDIATELY, credential = credential), clock)
        lock.unlockWithSecret(secret)
        lock.state.value.locked shouldBe false

        lock.onBackground()
        clock.advance(1L)
        lock.onForeground()

        lock.state.value.locked shouldBe true
    }

    @Test
    fun anImmediateLockGivesAHandOffItsGraceAndNoIdleTimer() {
        val clock = AppLockTest.FakeClock()
        val lock =
            AppLock(AppLockSettings(enabled = true, timeout = LockTimeout.IMMEDIATELY, credential = credential), clock)
        lock.unlockWithSecret(secret)

        clock.advance(60 * 60_000L)
        lock.checkIdle()
        lock.state.value.locked shouldBe false

        lock.onBackground(graceMillis = 3 * 60_000L)
        clock.advance(60_000L)
        lock.onForeground()
        lock.state.value.locked shouldBe false
    }
}
