package dev.npn.core.security

import dev.npn.core.util.MonotonicClock
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    fun idleLocksAfterTheTimeout() {
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
    fun aSettingsUpdateThatKeepsTheCredentialStaysLocked() {
        val lock = AppLock(AppLockSettings(enabled = true, credential = credential), FakeClock())

        lock.updateSettings(
            AppLockSettings(enabled = true, credential = credential, timeout = LockTimeout.FIVE_MINUTES),
        )

        lock.state.value.locked shouldBe true
        lock.state.value.timeout shouldBe LockTimeout.FIVE_MINUTES
    }

    @Test
    fun returningWithoutHavingLeftDoesNotLock() {
        val lock = AppLock(AppLockSettings(enabled = true, credential = credential), FakeClock())

        lock.unlockWithSecret(secret)
        // onCreate/onStart without a preceding stop: a cold start, or an activity recreated in place.
        lock.onForeground()

        lock.state.value.locked shouldBe false
    }

    @Test
    fun anUnknownStoredTimeoutFallsBackToThePlatformDefault() {
        LockTimeout.choices shouldContain LockTimeout.DEFAULT
        LockTimeout.fromWire(null) shouldBe LockTimeout.DEFAULT
        LockTimeout.fromWire("SOMETIME") shouldBe LockTimeout.DEFAULT
        LockTimeout.fromWire(LockTimeout.FIFTEEN_MINUTES.name) shouldBe LockTimeout.FIFTEEN_MINUTES
    }

    @Test
    fun disablingTheLockClearsTheHashAndUnlocks() {
        val lock = AppLock(AppLockSettings(enabled = true, credential = credential), FakeClock())

        lock.updateSettings(AppLockSettings(enabled = false))

        lock.state.value.enabled shouldBe false
        lock.state.value.locked shouldBe false
        lock.state.value.retryAtMillis shouldBe null
        lock.unlockWithSecret(secret) shouldBe UnlockResult.Disabled
    }

    @Test
    fun enablingOrChangingTheSecretDoesNotLockTheAppThatMadeTheChange() {
        val clock = FakeClock()
        val lock = AppLock(AppLockSettings(enabled = false), clock)
        lock.updateSettings(AppLockSettings(enabled = true, timeout = LockTimeout.ONE_MINUTE, credential = credential))
        lock.state.value.locked shouldBe false

        lock.onBackground()
        clock.advance(60_000L)
        lock.onForeground()
        lock.state.value.locked shouldBe true

        lock.updateSettings(
            AppLockSettings(
                enabled = true,
                timeout = LockTimeout.ONE_MINUTE,
                credential = credentialFor("another secret"),
            ),
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

    @Test
    fun aHandOffIsGivenAGraceThatStillEnds() {
        val clock = FakeClock()
        val lock = unlocked(LockTimeout.ONE_MINUTE, clock)

        // Two minutes in a picker: longer than the timeout, within the hand-off's grace.
        lock.onBackground(graceMillis = 3 * 60_000L)
        clock.advance(2 * 60_000L)
        lock.onForeground()
        lock.state.value.locked shouldBe false

        // The share sheet, then Home from there, and back hours later: the time away still counts.
        lock.onBackground(graceMillis = 3 * 60_000L)
        clock.advance(3 * 60 * 60_000L)
        lock.onForeground()
        lock.state.value.locked shouldBe true
    }

    @Test
    fun aGraceShorterThanTheTimeoutChangesNothing() {
        val clock = FakeClock()
        val lock = unlocked(LockTimeout.FIFTEEN_MINUTES, clock)

        lock.onBackground(graceMillis = 3 * 60_000L)
        clock.advance(10 * 60_000L)
        lock.onForeground()

        lock.state.value.locked shouldBe false
    }

    @Test
    fun theScreenGoingOffEndsAHandOffsGrace() {
        val clock = FakeClock()
        val lock = unlocked(LockTimeout.ONE_MINUTE, clock)

        lock.onBackground(graceMillis = 3 * 60_000L)
        clock.advance(30_000L)
        lock.onScreenOff()
        clock.advance(30_000L)
        lock.onForeground()

        lock.state.value.locked shouldBe true
    }

    @Test
    fun theScreenGoingOffInTheAppIsNoBackground() {
        val clock = FakeClock()
        val lock = unlocked(LockTimeout.ONE_MINUTE, clock)

        lock.onScreenOff()
        clock.advance(60 * 60_000L)
        lock.onForeground()

        lock.state.value.locked shouldBe false
    }

    @Test
    fun theBackoffOutlivesARestart() {
        val clock = FakeClock()
        val settings = AppLockSettings(enabled = true, timeout = LockTimeout.ONE_MINUTE, credential = credential)
        val before = AppLock(settings, clock)
        repeat(4) { before.unlockWithSecret("wrong") }
        val stored = before.attempts.value
        stored shouldBe LockAttempts(failures = 4, retryAtMillis = clock.now + 5_000L)

        val after = AppLock(settings, clock, attempts = stored)

        after.state.value.failedAttempts shouldBe 4
        after.unlockWithSecret(secret).shouldBeInstanceOf<UnlockResult.TooManyAttempts>()
        clock.advance(5_000L)
        after.unlockWithSecret("wrong") shouldBe UnlockResult.WrongSecret
        after.retryRemainingMillis() shouldBe 15_000L
        clock.advance(15_000L)
        after.unlockWithSecret(secret) shouldBe UnlockResult.Success
        after.attempts.value shouldBe LockAttempts()
    }

    @Test
    fun aStoredWaitIsHeldToWhatItsFailuresEarn() {
        val clock = FakeClock()
        val settings = AppLockSettings(enabled = true, credential = credential)
        // Android's clock starts over at a reboot, so a stored retry time can lie far ahead.
        val lock =
            AppLock(
                settings,
                clock,
                attempts =
                    LockAttempts(
                        failures = 4,
                        retryAtMillis =
                            clock.now + 36_000_000L,
                    ),
            )

        lock.retryRemainingMillis() shouldBe 5_000L
        clock.advance(5_000L)
        lock.unlockWithSecret(secret) shouldBe UnlockResult.Success
    }

    @Test
    fun storedAttemptsDoNotCountAgainstALockThatIsOff() {
        val lock = AppLock(AppLockSettings(enabled = false), FakeClock(), attempts = LockAttempts(failures = 9))

        lock.attempts.value shouldBe LockAttempts()
    }

    @Test
    fun aClockThatMovedBackHoldsAttemptsNoLongerThanTheWait() {
        val clock = FakeClock(now = 100_000_000L)
        val lock = AppLock(AppLockSettings(enabled = true, credential = credential), clock)
        repeat(7) {
            clock.advance(lock.retryRemainingMillis())
            lock.unlockWithSecret("wrong") shouldBe UnlockResult.WrongSecret
        }
        lock.retryRemainingMillis() shouldBe LockBackoff.MAX_DELAY_MS

        // The desktop's clock is the wall clock: set back three hours.
        clock.advance(-3 * 60 * 60_000L)
        lock.retryRemainingMillis() shouldBe LockBackoff.MAX_DELAY_MS
        clock.advance(LockBackoff.MAX_DELAY_MS)

        lock.retryRemainingMillis() shouldBe 0L
        lock.unlockWithSecret(secret) shouldBe UnlockResult.Success
    }

    @Test
    fun anAttemptJudgedAgainstAReplacedHashIsCheckedAgainstTheStoredOne() {
        val replacement = AppLockSettings(enabled = true, credential = credentialFor("another secret"))
        lateinit var lock: AppLock
        var checks = 0
        lock =
            AppLock(
                AppLockSettings(enabled = true, credential = credential),
                FakeClock(),
                verify = { typed, against ->
                    // The setting changes while the first (slow) check runs.
                    if (checks++ == 0) lock.updateSettings(replacement)
                    LockCrypto.verify(typed, against)
                },
            )

        lock.unlockWithSecret(secret) shouldBe UnlockResult.WrongSecret
        checks shouldBe 2
    }

    @Test
    fun waitingForTheUnlockEndsWhenItOpens() =
        runTest {
            val lock = AppLock(AppLockSettings(enabled = true, credential = credential), FakeClock())
            val waiting = launch { lock.awaitUnlocked() }
            runCurrent()
            waiting.isCompleted shouldBe false

            lock.unlockWithSecret(secret)
            runCurrent()

            waiting.isCompleted shouldBe true
        }

    @Test
    fun theNumericHintGoesWithTheCredential() {
        AppLockSettings.isNumeric("0123") shouldBe true
        AppLockSettings.isNumeric("12a4") shouldBe false
        AppLockSettings.isNumeric("") shouldBe false
        AppLockSettings(enabled = false, numeric = true).normalised().numeric shouldBe false
        AppLock(AppLockSettings(enabled = true, credential = credential, numeric = true), FakeClock())
            .state.value.numeric shouldBe true
    }

    private fun unlocked(
        timeout: LockTimeout,
        clock: FakeClock,
    ): AppLock =
        AppLock(AppLockSettings(enabled = true, timeout = timeout, credential = credential), clock).also {
            it.unlockWithSecret(secret) shouldBe UnlockResult.Success
        }

    private fun credentialFor(value: String): LockCredential =
        LockCrypto.create(value, iterations = LockCrypto.MIN_ITERATIONS, salt = ByteArray(LockCrypto.SALT_BYTES) { 7 })

    internal class FakeClock(
        var now: Long = 1_000_000L,
    ) : MonotonicClock {
        override fun elapsedMillis(): Long = now

        fun advance(millis: Long) {
            now += millis
        }
    }
}
