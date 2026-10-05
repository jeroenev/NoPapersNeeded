package dev.npn.app

import dev.npn.app.ui.update.UpdateNoticeViewModel
import dev.npn.core.update.AppRelease
import dev.npn.core.update.LatestReleaseSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UpdateNoticeViewModelTest : MainDispatcherTest() {
    private val release = AppRelease("1.1.0", "https://example.invalid/releases/v1.1.0")

    private class CountingSource(
        private val release: AppRelease?,
    ) : LatestReleaseSource {
        var calls = 0

        override suspend fun latest(): AppRelease? {
            calls++
            return release
        }
    }

    private fun vm(
        settings: FakeSettingsRepository,
        source: LatestReleaseSource,
        current: String = "1.0.0",
        platformChecks: Boolean = true,
    ) = UpdateNoticeViewModel(settings, source, current, platformChecks)

    @Test
    fun offersANewerRelease() =
        runTest(dispatcher) {
            val vm = vm(FakeSettingsRepository(), CountingSource(release))
            collecting(vm.notice)
            advanceUntilIdle()
            vm.notice.value shouldBe release
        }

    @Test
    fun ignoresTheRunningOrAnOlderVersion() =
        runTest(dispatcher) {
            val vm = vm(FakeSettingsRepository(), CountingSource(release), current = "1.1.0")
            collecting(vm.notice)
            advanceUntilIdle()
            vm.notice.value shouldBe null
        }

    @Test
    fun dismissingHidesThatVersionAndRemembersIt() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val vm = vm(settings, CountingSource(release))
            collecting(vm.notice)
            advanceUntilIdle()
            vm.dismiss(release)
            advanceUntilIdle()
            vm.notice.value shouldBe null
            settings.settings.first().dismissedUpdateVersion shouldBe "1.1.0"
        }

    @Test
    fun disabledCheckDoesNotContactTheSourceUntilEnabled() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository().apply { updateSettings { it.copy(updateCheckEnabled = false) } }
            val source = CountingSource(release)
            val vm = vm(settings, source)
            collecting(vm.notice)
            advanceUntilIdle()
            source.calls shouldBe 0
            vm.notice.value shouldBe null

            settings.updateSettings { it.copy(updateCheckEnabled = true) }
            advanceUntilIdle()
            source.calls shouldBe 1
            vm.notice.value shouldBe release
        }

    @Test
    fun platformThatUpdatesItselfNeverChecks() =
        runTest(dispatcher) {
            val source = CountingSource(release)
            val vm = vm(FakeSettingsRepository(), source, platformChecks = false)
            collecting(vm.notice)
            advanceUntilIdle()
            source.calls shouldBe 0
            vm.notice.value shouldBe null
        }
}
