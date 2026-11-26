package dev.npn.app

import dev.npn.app.ui.settings.LockChange
import dev.npn.app.ui.settings.SecurityDialog
import dev.npn.core.security.AppLock
import dev.npn.core.security.AppLockSettings
import dev.npn.core.security.LockCrypto
import dev.npn.core.security.LockTimeout
import dev.npn.core.security.UnlockResult
import dev.npn.core.util.MonotonicClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Whoever picks up the unlocked app must not be able to turn the lock off or replace its PIN:
 * Settings changes a lock that is on only after the current PIN (or the device's own unlock).
 */
class SettingsAppLockTest : MainDispatcherTest() {
    private val pin = "2468"
    private val credential =
        LockCrypto.create(pin, iterations = LockCrypto.MIN_ITERATIONS, salt = ByteArray(LockCrypto.SALT_BYTES))
    private val lockSettings =
        AppLockSettings(enabled = true, timeout = LockTimeout.FIVE_MINUTES, credential = credential)
    private val settings = FakeSettingsRepository().apply { updateSettings { it.copy(appLock = lockSettings) } }
    private val lock =
        AppLock(lockSettings, MonotonicClock { 0L }).also { it.unlockWithSecret(pin) shouldBe UnlockResult.Success }

    private fun TestScope.vm() =
        settingsVm(settings, lock = lock, lockDispatcher = dispatcher).also { collecting(it.paneState) }

    @Test
    fun turningTheLockOffAsksForTheCurrentPin() =
        runTest(dispatcher) {
            val model = vm()

            model.setAppLockEnabled(false)
            advanceUntilIdle()
            settings.settings.value.appLock.enabled shouldBe true
            model.paneState.value.securityDialog shouldBe SecurityDialog.ConfirmCurrent(LockChange.TurnOff)

            model.confirmAppLockChange("1357")
            advanceUntilIdle()
            settings.settings.value.appLock.enabled shouldBe true
            model.paneState.value.securityCheck.outcome shouldBe UnlockResult.WrongSecret

            model.confirmAppLockChange(pin)
            advanceUntilIdle()
            settings.settings.value.appLock.enabled shouldBe false
            model.paneState.value.securityDialog shouldBe null
        }

    @Test
    fun aNewTimeoutWaitsForTheCurrentPin() =
        runTest(dispatcher) {
            val model = vm()

            model.setAppLockTimeout(LockTimeout.FIFTEEN_MINUTES)
            advanceUntilIdle()
            settings.settings.value.appLock.timeout shouldBe LockTimeout.FIVE_MINUTES

            model.confirmAppLockChange(pin)
            advanceUntilIdle()
            settings.settings.value.appLock.timeout shouldBe LockTimeout.FIFTEEN_MINUTES
        }

    @Test
    fun aNewPinIsTakenOnlyAfterTheCurrentOne() =
        runTest(dispatcher) {
            val model = vm()

            // Straight to the new PIN, as a dialog opened by other means would send it: refused.
            model.setAppLockPin("9999")
            advanceUntilIdle()
            settings.settings.value.appLock.credential shouldBe credential
            model.paneState.value.securityDialog shouldBe SecurityDialog.ConfirmCurrent(LockChange.ChangePin)

            model.confirmAppLockChange(pin)
            advanceUntilIdle()
            model.paneState.value.securityDialog shouldBe SecurityDialog.ChangePin
            model.setAppLockPin("9999")
            advanceUntilIdle()

            val saved = settings.settings.value.appLock
            LockCrypto.verify("9999", checkNotNull(saved.credential)) shouldBe true
            saved.numeric shouldBe true
            model.paneState.value.securityDialog shouldBe null
        }

    @Test
    fun theDevicesOwnUnlockConfirmsAChange() =
        runTest(dispatcher) {
            val model = vm()

            model.setAppLockEnabled(false)
            model.confirmAppLockChangeWithDevice()
            advanceUntilIdle()

            settings.settings.value.appLock.enabled shouldBe false
        }

    @Test
    fun wrongPinsInSettingsCountAgainstTheLocksBackoff() =
        runTest(dispatcher) {
            val model = vm()
            model.setAppLockEnabled(false)

            repeat(4) {
                model.confirmAppLockChange("1357")
                advanceUntilIdle()
            }
            model.confirmAppLockChange(pin)
            advanceUntilIdle()

            model.paneState.value.securityCheck.outcome
                .shouldBeInstanceOf<UnlockResult.TooManyAttempts>()
            settings.settings.value.appLock.enabled shouldBe true
            lock.state.value.failedAttempts shouldBe 4
        }

    @Test
    fun lockNowLocks() =
        runTest(dispatcher) {
            vm().lockAppNow()

            lock.state.value.locked shouldBe true
        }

    @Test
    fun aDigitsOnlyPinOffersANumberPadAndAnyOtherDoesNot() =
        runTest(dispatcher) {
            val off = FakeSettingsRepository()
            val model = settingsVm(off, lockDispatcher = dispatcher).also { collecting(it.paneState) }

            model.setAppLockPin("abcd")
            advanceUntilIdle()

            off.settings.value.appLock.enabled shouldBe true
            off.settings.value.appLock.numeric shouldBe false
        }
}
