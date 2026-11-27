package dev.npn.app.ui.settings

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.failure
import dev.npn.core.app.LibraryDevice
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_devices_retire_failed
import kotlin.test.Test

/** The devices page: the list as the session reads it, and a retirement that shows at once or says why it failed. */
class DevicesViewModelTest : MainDispatcherTest() {
    private val other = LibraryDevice("other-device", false, 5_000L, retired = false, silent = false)

    @Test
    fun aRetiredDeviceShowsRetiredAndCanBeBroughtBack() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.devices.list.value = session.devices.list.value + other
            val vm = DevicesViewModel(session)
            advanceUntilIdle()
            vm.list.value?.map { it.deviceId } shouldBe listOf("this-device", "other-device")

            vm.retire("other-device")
            advanceUntilIdle()
            vm.list.value
                ?.single { it.deviceId == "other-device" }
                ?.retired shouldBe true

            vm.unretire("other-device")
            advanceUntilIdle()
            vm.list.value
                ?.single { it.deviceId == "other-device" }
                ?.retired shouldBe false
        }

    @Test
    fun aRetirementThatFailsSaysWhy() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.devices.list.value = session.devices.list.value + other
            session.devices.fail = { throw IllegalStateException("writes are blocked") }
            val vm = DevicesViewModel(session)
            val messages = collecting(vm.messages.map { it.text })

            vm.retire("other-device")
            advanceUntilIdle()

            messages shouldContainExactly listOf(failure(Res.string.common_devices_retire_failed, "writes are blocked"))
            vm.list.value
                ?.single { it.deviceId == "other-device" }
                ?.retired shouldBe false
        }
}
