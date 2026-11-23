package dev.npn.app

import dev.npn.app.ui.whatsnew.WhatsNewGateViewModel
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class WhatsNewGateTest : MainDispatcherTest() {
    @Test
    fun aFreshInstallRecordsTheVersionWithoutShowingNotes() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val gate = WhatsNewGateViewModel(settings, currentVersion = "1.0.0")
            advanceUntilIdle()

            gate.show.value shouldBe false
            settings.settings.first().lastSeenVersion shouldBe "1.0.0"
        }

    @Test
    fun aVersionChangeShowsTheNotesOnce() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            settings.edit { it.copy(lastSeenVersion = "1.0.0") }

            val gate = WhatsNewGateViewModel(settings, currentVersion = "1.1.0")
            advanceUntilIdle()
            gate.show.value shouldBe true

            gate.dismiss()
            advanceUntilIdle()
            gate.show.value shouldBe false
            settings.settings.first().lastSeenVersion shouldBe "1.1.0"
        }

    @Test
    fun anExistingLibrarySeesTheFirstBundledNotes() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            settings.edit { it.copy(libraryLocation = "/library") }

            val gate = WhatsNewGateViewModel(settings, currentVersion = "1.0.0")
            advanceUntilIdle()

            gate.show.value shouldBe true
        }

    @Test
    fun theSameVersionNeverShowsTheNotes() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            settings.edit { it.copy(lastSeenVersion = "1.0.0") }

            val gate = WhatsNewGateViewModel(settings, currentVersion = "1.0.0")
            advanceUntilIdle()

            gate.show.value shouldBe false
            settings.settings.first().lastSeenVersion shouldBe "1.0.0"
        }
}
