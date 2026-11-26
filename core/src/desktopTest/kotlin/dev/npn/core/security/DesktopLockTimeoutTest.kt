package dev.npn.core.security

import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** A desktop window has no reliable background: idle time is what locks it, so it must be a time. */
class DesktopLockTimeoutTest {
    private val credential =
        LockCrypto.create(
            "correct horse",
            iterations = LockCrypto.MIN_ITERATIONS,
            salt = ByteArray(LockCrypto.SALT_BYTES),
        )

    @Test
    fun immediatelyIsNotOfferedAndANewLockWaitsFiveMinutes() {
        LockTimeout.choices shouldNotContain LockTimeout.IMMEDIATELY
        LockTimeout.DEFAULT shouldBe LockTimeout.FIVE_MINUTES
        AppLockSettings().timeout shouldBe LockTimeout.FIVE_MINUTES
        LockTimeout.fromWire(LockTimeout.IMMEDIATELY.name) shouldBe LockTimeout.FIVE_MINUTES
        AppLockSettings(enabled = true, timeout = LockTimeout.IMMEDIATELY, credential = credential)
            .normalised()
            .timeout shouldBe LockTimeout.FIVE_MINUTES
    }

    @Test
    fun aNewLockLocksAnIdleWindow() {
        val clock = AppLockTest.FakeClock()
        // As Settings turns it on: no timeout picked.
        val lock = AppLock(AppLockSettings(), clock)
        lock.updateSettings(AppLockSettings(enabled = true, credential = credential))

        clock.advance(5 * 60_000L - 1)
        lock.checkIdle()
        lock.state.value.locked shouldBe false
        clock.advance(1L)
        lock.checkIdle()

        lock.state.value.locked shouldBe true
    }
}
