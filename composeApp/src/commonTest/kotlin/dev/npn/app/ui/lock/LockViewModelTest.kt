package dev.npn.app.ui.lock

import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.core.security.AppLock
import dev.npn.core.security.AppLockSettings
import dev.npn.core.security.LockCrypto
import dev.npn.core.security.UnlockResult
import dev.npn.core.util.MonotonicClock
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class LockViewModelTest : MainDispatcherTest() {
    private val secret = "1234"
    private val credential =
        LockCrypto.create(secret, iterations = LockCrypto.MIN_ITERATIONS, salt = ByteArray(LockCrypto.SALT_BYTES))

    @Test
    fun aWrongSecretLeavesTheAppLockedAndSaysSo() =
        runTest(dispatcher) {
            val lock = AppLock(AppLockSettings(enabled = true, credential = credential), MonotonicClock { 0L })
            val vm = LockViewModel(lock, StandardTestDispatcher(testScheduler))
            collecting(vm.state)

            vm.unlock("0000")
            testScheduler.advanceUntilIdle()

            vm.state.value.outcome shouldBe UnlockResult.WrongSecret
            vm.state.value.lock.locked shouldBe true
        }

    @Test
    fun theRightSecretUnlocks() =
        runTest(dispatcher) {
            val lock = AppLock(AppLockSettings(enabled = true, credential = credential), MonotonicClock { 0L })
            val vm = LockViewModel(lock, StandardTestDispatcher(testScheduler))
            collecting(vm.state)

            vm.unlock(secret)
            testScheduler.advanceUntilIdle()

            lock.state.value.locked shouldBe false
            vm.state.value.outcome shouldBe null
        }

    @Test
    fun theDeviceUnlockOpensItWithoutASecret() =
        runTest(dispatcher) {
            val lock = AppLock(AppLockSettings(enabled = true, credential = credential), MonotonicClock { 0L })
            val vm = LockViewModel(lock, StandardTestDispatcher(testScheduler))
            collecting(vm.state)

            vm.unlockWithDevice()

            lock.state.value.locked shouldBe false
        }

    @Test
    fun repeatedWrongSecretsReportTheWait() =
        runTest(dispatcher) {
            val now = 1_000L
            val lock = AppLock(AppLockSettings(enabled = true, credential = credential), MonotonicClock { now })
            val vm = LockViewModel(lock, StandardTestDispatcher(testScheduler))
            collecting(vm.state)

            repeat(4) {
                vm.unlock("0000")
                testScheduler.advanceUntilIdle()
            }
            vm.state.value.outcome shouldBe UnlockResult.WrongSecret
            vm.retryRemainingMillis() shouldBe 5_000L

            // The next attempt is refused during the wait, without being hashed again.
            vm.unlock("0000")
            testScheduler.advanceUntilIdle()
            vm.state.value.outcome
                .shouldBeInstanceOf<UnlockResult.TooManyAttempts>()
            vm.state.value.lock.locked shouldBe true
        }
}
