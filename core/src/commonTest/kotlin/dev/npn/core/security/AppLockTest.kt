package dev.npn.core.security

import dev.npn.core.util.EpochClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class AppLockTest {
    private val secret = "correct horse"
    private val credential = credentialFor(secret)

    @Test
    fun disabledStateNeverLocks() {
        val clock = FakeClock()
        val lock = AppLock(AppLockSettings(enabled = false), clock)

        lock.state.value.locked shouldBe false
        lock.lockNow()
        lock.onBackground()
        clock.advance(15 * 60_000L)
        lock.onForeground()
        lock.checkIdle()

        lock.state.value.locked shouldBe false
        lock.unlockWithSecret(secret) shouldBe UnlockResult.Disabled
    }

    @Test
    fun aColdStartWithTheLockOnIsLocked() {
        val lock = AppLock(AppLockSettings(enabled = true, credential = credential), FakeClock())

        lock.state.value.locked shouldBe true
        lock.state.value.enabled shouldBe true
    }

    @Test
    fun theWrongSecretKeepsItLockedAndTheRightOneOpensIt() {
        val lock = AppLock(AppLockSettings(enabled = true, credential = credential), FakeClock())

        lock.unlockWithSecret("wrong") shouldBe UnlockResult.WrongSecret
        lock.state.value.locked shouldBe true

        lock.unlockWithSecret(secret) shouldBe UnlockResult.Success
        lock.state.value.locked shouldBe false
        lock.state.value.failedAttempts shouldBe 0
    }

    @Test
    fun immediateTimeoutLocksOnEveryReturn() {
        val clock = FakeClock()
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
    fun theTimeoutIsMeasuredFromWhenTheAppLeftTheForeground() {
        val clock = FakeClock()
        val lock =
            AppLock(
                AppLockSettings(enabled = true, timeout = LockTimeout.FIVE_MINUTES, credential = credential),
                clock,
            )
        lock.unlockWithSecret(secret)

        lock.onBackground()
        clock.advance(5 * 60_000L - 1)
        lock.onForeground()
        lock.state.value.locked shouldBe false

        lock.onBackground()
        clock.advance(5 * 60_000L)
        lock.onForeground()
        lock.state.value.locked shouldBe true
    }

    @Test
    fun oneAndFifteenMinutesAreTheirOwnIntervals() {
        for ((timeout, away) in listOf(
            LockTimeout.ONE_MINUTE to 60_000L,
            LockTimeout.FIFTEEN_MINUTES to 15 * 60_000L,
        )) {
            val clock = FakeClock()
            val lock = AppLock(AppLockSettings(enabled = true, timeout = timeout, credential = credential), clock)
            lock.unlockWithSecret(secret)
            lock.onBackground()
            clock.advance(away - 1)
            lock.onForeground()
            lock.state.value.locked shouldBe false
            lock.onBackground()
            clock.advance(away)
            lock.onForeground()
            lock.state.value.locked shouldBe true
        }
    }

    @Test
    fun idleLocksOnlyForATimedTimeout() {
        val clock = FakeClock()
        val timed =
            AppLock(
                AppLockSettings(enabled = true, timeout = LockTimeout.ONE_MINUTE, credential = credential),
                clock,
            )
        timed.unlockWithSecret(secret)
        clock.advance(59_999L)
        timed.checkIdle()
        timed.state.value.locked shouldBe false
        clock.advance(1L)
        timed.checkIdle()
        timed.state.value.locked shouldBe true

        val immediate =
            AppLock(
                AppLockSettings(enabled = true, timeout = LockTimeout.IMMEDIATELY, credential = credential),
                FakeClock(),
            )
        immediate.unlockWithSecret(secret)
        immediate.checkIdle()
        immediate.state.value.locked shouldBe false
    }

    @Test
    fun interactionRestartsTheIdleTimer() {
        val clock = FakeClock()
        val lock =
            AppLock(
                AppLockSettings(enabled = true, timeout = LockTimeout.ONE_MINUTE, credential = credential),
                clock,
            )
        lock.unlockWithSecret(secret)
        clock.advance(50_000L)
        lock.onInteraction()
        clock.advance(50_000L)
        lock.checkIdle()
        lock.state.value.locked shouldBe false
        clock.advance(10_000L)
        lock.checkIdle()
        lock.state.value.locked shouldBe true
    }

    @Test
    fun repeatedFailuresBackOffAndThenReset() {
        val clock = FakeClock()
        val lock = AppLock(AppLockSettings(enabled = true, credential = credential), clock)

        repeat(3) { lock.unlockWithSecret("wrong") shouldBe UnlockResult.WrongSecret }
        lock.state.value.retryAtMillis shouldBe null

        // The fourth wrong secret starts the backoff.
        lock.unlockWithSecret("wrong") shouldBe UnlockResult.WrongSecret
        val retryAt = lock.state.value.retryAtMillis
        retryAt shouldBe clock.now + 5_000L

        // A correct secret is refused while the wait holds, without being checked.
        lock.unlockWithSecret(secret).shouldBeInstanceOf<UnlockResult.TooManyAttempts>()
        lock.state.value.locked shouldBe true

        clock.advance(5_000L)
        lock.unlockWithSecret(secret) shouldBe UnlockResult.Success
        lock.state.value.failedAttempts shouldBe 0
        lock.state.value.retryAtMillis shouldBe null
    }

    @Test
    fun theBackoffGrowsAndIsCapped() {
        LockBackoff.retryDelayMillis(1) shouldBe 0L
        LockBackoff.retryDelayMillis(3) shouldBe 0L
        LockBackoff.retryDelayMillis(4) shouldBe 5_000L
        LockBackoff.retryDelayMillis(5) shouldBe 15_000L
        LockBackoff.retryDelayMillis(6) shouldBe 60_000L
        LockBackoff.retryDelayMillis(7) shouldBe 300_000L
        LockBackoff.retryDelayMillis(50) shouldBe 300_000L
    }

    @Test
    fun disablingTheLockClearsTheHashAndUnlocks() {
        val lock = AppLock(AppLockSettings(enabled = true, credential = credential), FakeClock())
        lock.state.value.credential shouldBe credential

        lock.updateSettings(AppLockSettings(enabled = false))

        lock.state.value.enabled shouldBe false
        lock.state.value.locked shouldBe false
        lock.state.value.credential shouldBe null
        lock.state.value.retryAtMillis shouldBe null
    }

    @Test
    fun enablingOrChangingTheSecretDoesNotLockTheAppThatMadeTheChange() {
        val lock = AppLock(AppLockSettings(enabled = false), FakeClock())
        lock.updateSettings(AppLockSettings(enabled = true, credential = credential))
        lock.state.value.locked shouldBe false

        lock.onBackground()
        lock.onForeground()
        lock.state.value.locked shouldBe true

        lock.updateSettings(
            AppLockSettings(enabled = true, credential = credentialFor("another secret")),
        )
        lock.state.value.locked shouldBe false
        lock.state.value.failedAttempts shouldBe 0
    }

    @Test
    fun anEnabledLockWithoutAUsableCredentialIsDisabled() {
        val clock = FakeClock()
        val lock = AppLock(AppLockSettings(enabled = true, credential = null), clock)

        lock.state.value.enabled shouldBe false
        lock.state.value.locked shouldBe false
    }

    @Test
    fun aSavedCredentialWithoutTheSecretStillVerifiesIt() {
        val lock = AppLock(AppLockSettings(enabled = true, credential = credential), FakeClock())
        val saved = AppLockSettings(enabled = true, credential = credential).normalised()

        lock.updateSettings(saved)
        lock.unlockWithSecret(secret) shouldBe UnlockResult.Success
    }

    private fun credentialFor(value: String): LockCredential =
        LockCrypto.create(value, iterations = LockCrypto.MIN_ITERATIONS, salt = ByteArray(LockCrypto.SALT_BYTES) { 7 })

    private class FakeClock(
        var now: Long = 1_000_000L,
    ) : EpochClock {
        override fun nowMillis(): Long = now

        fun advance(millis: Long) {
            now += millis
        }
    }
}
