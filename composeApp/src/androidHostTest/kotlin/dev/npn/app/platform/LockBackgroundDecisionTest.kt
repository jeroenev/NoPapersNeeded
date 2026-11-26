package dev.npn.app.platform

import dev.npn.core.security.AppLock
import dev.npn.core.security.AppLockSettings
import dev.npn.core.security.LockCrypto
import dev.npn.core.security.LockTimeout
import dev.npn.core.security.UnlockResult
import dev.npn.core.util.MonotonicClock
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The activity lifecycle as the app lock reads it ([LockLifecycle]), against a real [AppLock]. */
class LockBackgroundDecisionTest {
    private val secret = "2468"
    private var now = 1_000_000L
    private val clock = MonotonicClock { now }

    private fun unlocked(timeout: LockTimeout): Pair<AppLock, LockLifecycle> {
        val credential =
            LockCrypto.create(secret, iterations = LockCrypto.MIN_ITERATIONS, salt = ByteArray(LockCrypto.SALT_BYTES))
        val lock = AppLock(AppLockSettings(enabled = true, timeout = timeout, credential = credential), clock)
        lock.unlockWithSecret(secret) shouldBe UnlockResult.Success
        return lock to LockLifecycle(lock)
    }

    private fun LockLifecycle.leave(
        handoff: Boolean = false,
        finishing: Boolean = false,
        screenOn: Boolean = true,
    ) = onStop(changingConfigurations = false, finishing = finishing, screenOn = screenOn, handoff = handoff)

    private fun advanceMinutes(minutes: Int) {
        now += minutes * 60_000L
    }

    @Test
    fun backWithoutLeaveHintLocksOnReturn() {
        val (lock, lifecycle) = unlocked(LockTimeout.IMMEDIATELY)

        lifecycle.leave(finishing = true)
        lifecycle.onStart()

        lock.state.value.locked shouldBe true
    }

    @Test
    fun aQuickHandoffDoesNotLock() {
        val (lock, lifecycle) = unlocked(LockTimeout.IMMEDIATELY)

        lifecycle.leave(handoff = true)
        advanceMinutes(1)
        lifecycle.onStart()

        lock.state.value.locked shouldBe false
    }

    @Test
    fun handoffThenHomeThenReturnLocks() {
        val (lock, lifecycle) = unlocked(LockTimeout.IMMEDIATELY)

        // The share sheet stops the activity; the user goes Home from it and comes back hours later.
        lifecycle.leave(handoff = true)
        advanceMinutes(4 * 60)
        lifecycle.onStart()

        lock.state.value.locked shouldBe true
    }

    @Test
    fun aHandoffNeverShortensALongerTimeout() {
        val (lock, lifecycle) = unlocked(LockTimeout.FIFTEEN_MINUTES)

        lifecycle.leave(handoff = true)
        advanceMinutes(10)
        lifecycle.onStart()
        lock.state.value.locked shouldBe false

        lifecycle.leave(handoff = true)
        advanceMinutes(15)
        lifecycle.onStart()
        lock.state.value.locked shouldBe true
    }

    @Test
    fun theScreenGoingOffFromThePickerLocksOnReturn() {
        val (lock, lifecycle) = unlocked(LockTimeout.IMMEDIATELY)

        lifecycle.leave(handoff = true)
        lifecycle.onScreenOff()
        advanceMinutes(1)
        lifecycle.onStart()

        lock.state.value.locked shouldBe true
    }

    @Test
    fun finishingAndScreenOffOverrideHandoff() {
        val (finished, finishing) = unlocked(LockTimeout.IMMEDIATELY)
        finishing.leave(handoff = true, finishing = true)
        finishing.onStart()
        finished.state.value.locked shouldBe true

        val (dark, screenOff) = unlocked(LockTimeout.IMMEDIATELY)
        screenOff.leave(handoff = true, screenOn = false)
        screenOff.onStart()
        dark.state.value.locked shouldBe true
    }

    @Test
    fun aRecreatedActivityIsNotLeaving() {
        val (lock, lifecycle) = unlocked(LockTimeout.IMMEDIATELY)

        lifecycle.onStop(changingConfigurations = true, finishing = true, screenOn = false, handoff = false)
        advanceMinutes(1)
        lifecycle.onStart()

        lock.state.value.locked shouldBe false
    }

    @Test
    fun handoffIsConsumedAndCanBeClearedAfterFailure() {
        LockActivityHandoff.clear()
        LockActivityHandoff.started()
        assertTrue(LockActivityHandoff.consume())
        assertFalse(LockActivityHandoff.consume())
        LockActivityHandoff.started()
        LockActivityHandoff.clear()
        assertFalse(LockActivityHandoff.consume())
    }
}
