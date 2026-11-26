package dev.npn.app

import dev.npn.app.ui.settings.SettingsViewModel
import dev.npn.app.ui.settings.TextSetting
import dev.npn.core.ai.AiCheck
import dev.npn.core.ai.AiCheckOutcome
import dev.npn.core.ai.AiCheckStatus
import dev.npn.core.ai.AiConfig
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiSelfTestReport
import dev.npn.core.security.AppLock
import dev.npn.core.security.AppLockSettings
import dev.npn.core.util.MonotonicClock
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import kotlin.test.Test

class SettingsAiSelfTestTest : MainDispatcherTest() {
    /** A model whose pane state is collected, as the screen collects it, so tests can read the self-test's. */
    private fun TestScope.vm(
        settings: FakeSettingsRepository,
        selfTest: suspend (AiConfig) -> AiSelfTestReport,
    ) = SettingsViewModel(
        settings,
        FakeLibrarySession(),
        selfTest,
        lock = AppLock(AppLockSettings(), MonotonicClock { 0L }),
    ).also { collecting(it.paneState) }

    /** The self-test asks the endpoint the screen shows, typed values included. */
    @Test
    fun aRequestFromTheScreenUsesWhatIsTyped() =
        runTest(dispatcher) {
            var asked: AiConfig? = null
            val model =
                vm(settings = aiSettings()) { config ->
                    asked = config
                    report(AiCheckStatus.PASSED)
                }
            model.type(TextSetting.AI_MODEL, "llama3.3")
            model.runAiSelfTest()
            runCurrent()

            asked?.model shouldBe "llama3.3"
        }

    @Test
    fun aFailedVisionProbeDoesNotTurnOffAReplacementEndpointsOcr() =
        runTest(dispatcher) {
            val settings = aiSettings()
            val answer = CompletableDeferred<AiSelfTestReport>()
            val model = vm(settings = settings) { answer.await() }
            model.runAiSelfTest()
            runCurrent()
            model.setAiProvider(AiProvider.CUSTOM)
            runCurrent()

            answer.complete(report(AiCheckStatus.FAILED))
            runCurrent()

            settings.settingsFlow.value.aiOcrAuto shouldBe true
            model.paneState.value.aiSelfTest.report shouldBe null
            model.paneState.value.aiSelfTest.running shouldBe false
        }

    @Test
    fun aFailedVisionProbeDoesNotTurnOffAModelStillBeingTyped() =
        runTest(dispatcher) {
            val settings = aiSettings()
            val answer = CompletableDeferred<AiSelfTestReport>()
            val model = vm(settings = settings) { answer.await() }
            model.runAiSelfTest()
            runCurrent()
            model.type(TextSetting.AI_MODEL, "replacement-vision-model")
            runCurrent()

            answer.complete(report(AiCheckStatus.FAILED))
            runCurrent()

            settings.settingsFlow.value.aiOcrAuto shouldBe true
            model.paneState.value.aiSelfTest.report shouldBe null
            advanceUntilIdle()
            settings.settingsFlow.value.aiModel shouldBe "replacement-vision-model"
            settings.settingsFlow.value.aiOcrAuto shouldBe true
        }

    private fun aiSettings() =
        FakeSettingsRepository().apply {
            updateSettings {
                it.copy(
                    aiEnabled = true,
                    aiProvider = AiProvider.OLLAMA,
                    aiModel = "qwen3:8b",
                    aiOcrAuto = true,
                )
            }
        }

    @Test
    fun aChangedEndpointWhileTheOcrSaveWaitsDropsTheOldVerdict() =
        runTest(dispatcher) {
            val gate = CompletableDeferred<Unit>()
            val settings = aiSettings().apply { editGate = gate }
            val model = vm(settings) { report(AiCheckStatus.FAILED) }
            model.runAiSelfTest()
            runCurrent()
            model.paneState.value.aiSelfTest.running shouldBe true

            settings.updateSettings { it.copy(aiModel = "replacement-vision-model") }
            gate.complete(Unit)
            advanceUntilIdle()

            settings.settingsFlow.value.aiOcrAuto shouldBe true
            model.paneState.value.aiSelfTest.report shouldBe null
            model.paneState.value.aiSelfTest.running shouldBe false
        }

    private fun report(vision: AiCheckStatus) =
        AiSelfTestReport(
            listOf(
                AiCheckOutcome(AiCheck.API, AiCheckStatus.PASSED),
                AiCheckOutcome(AiCheck.MESSAGES, AiCheckStatus.PASSED),
                AiCheckOutcome(AiCheck.VISION, vision),
            ),
        )

    @Test
    fun aFailedVisionCheckSwitchesAiPageReadingOff() =
        runTest(dispatcher) {
            // The one setting this test is allowed to change on the user's behalf: page reading on
            // a model that cannot read images is a paid request per page that returns nothing.
            val settings = aiSettings()
            val model = vm(settings = settings, selfTest = { report(AiCheckStatus.FAILED) })

            model.runAiSelfTest()
            advanceUntilIdle()

            settings.settingsFlow.value.aiOcrAuto shouldBe false
            model.paneState.value.aiSelfTest.aiOcrTurnedOff shouldBe true
            model.paneState.value.aiSelfTest.running shouldBe false
        }

    @Test
    fun aFailedSwitchOffOfPageReadingIsReportedNotClaimed() =
        runTest(dispatcher) {
            val settings = aiSettings().apply { failEdit = { error("read-only file system") } }
            val model = vm(settings = settings, selfTest = { report(AiCheckStatus.FAILED) })
            val messages = collecting(model.messages.map { it.text })

            model.runAiSelfTest()
            advanceUntilIdle()

            model.paneState.value.aiSelfTest.aiOcrTurnedOff shouldBe false
            model.paneState.value.aiSelfTest.running shouldBe false
            messages shouldContainExactly
                listOf(failure(Res.string.common_save_setting_failed, "read-only file system"))
        }

    @Test
    fun aPassingOrSkippedVisionCheckLeavesAiPageReadingAlone() =
        runTest(dispatcher) {
            listOf(AiCheckStatus.PASSED, AiCheckStatus.SKIPPED).forEach { status ->
                val settings = aiSettings()
                val model = vm(settings = settings, selfTest = { report(status) })

                model.runAiSelfTest()
                advanceUntilIdle()

                settings.settingsFlow.value.aiOcrAuto shouldBe true
                model.paneState.value.aiSelfTest.aiOcrTurnedOff shouldBe false
            }
        }

    @Test
    fun aFailedVisionCheckReportsNoChangeWhenPageReadingWasAlreadyOff() =
        runTest(dispatcher) {
            // Nothing was switched off, so the screen must not claim it was.
            val settings = aiSettings().apply { updateSettings { it.copy(aiOcrAuto = false) } }
            val model = vm(settings = settings, selfTest = { report(AiCheckStatus.FAILED) })

            model.runAiSelfTest()
            advanceUntilIdle()

            model.paneState.value.aiSelfTest.aiOcrTurnedOff shouldBe false
            model.paneState.value.aiSelfTest.report
                ?.statusOf(AiCheck.VISION) shouldBe AiCheckStatus.FAILED
        }
}
