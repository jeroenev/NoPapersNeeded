package dev.npn.app

import androidx.lifecycle.ViewModelStore
import dev.npn.app.ui.settings.TextSetting
import dev.npn.core.ai.AiProvider
import dev.npn.core.ai.AiReasoningLevel
import dev.npn.core.ai.AiTagging
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** The AI endpoint on the Settings screen: provider, base URL, key, model and tagging limits. */
@OptIn(ExperimentalCoroutinesApi::class)
class SettingsAiEndpointTest : MainDispatcherTest() {
    @Test
    fun aiEditsPersistThroughRepository() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = settingsVm(settings = settings)
            model.edit { it.copy(aiEnabled = true) }
            model.setAiProvider(AiProvider.ANTHROPIC)
            model.edit { it.copy(aiBaseUrl = "http://localhost:11434") }
            model.edit { it.copy(aiApiKey = "secret") }
            model.edit { it.copy(aiModel = "qwen3:8b") }
            model.edit { it.copy(aiReasoningAssistant = AiReasoningLevel.HIGH) }
            model.edit { it.copy(aiReasoningEdit = AiReasoningLevel.MEDIUM) }
            model.edit { it.copy(aiMaxTagsPerDoc = 5) }
            model.edit { it.copy(aiMaxNewTagsPerDoc = 3) }
            advanceUntilIdle()
            settings.settingsFlow.value?.aiEnabled shouldBe true
            settings.settingsFlow.value?.aiProvider shouldBe AiProvider.ANTHROPIC
            settings.settingsFlow.value?.aiBaseUrl shouldBe "http://localhost:11434"
            settings.settingsFlow.value?.aiApiKey shouldBe "secret"
            settings.settingsFlow.value?.aiModel shouldBe "qwen3:8b"
            settings.settingsFlow.value?.aiReasoningAssistant shouldBe AiReasoningLevel.HIGH
            settings.settingsFlow.value?.aiReasoningEdit shouldBe AiReasoningLevel.MEDIUM
            settings.settingsFlow.value?.aiMaxTagsPerDoc shouldBe 5
            settings.settingsFlow.value?.aiMaxNewTagsPerDoc shouldBe 3
        }

    /**
     * Each save of a device setting writes the whole settings file: what is typed into an endpoint
     * field shows at once, and only the last value is saved, once typing pauses.
     */
    @Test
    fun anEndpointFieldIsSavedOnceTypingPauses() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = settingsVm(settings = settings)
            collecting(model.state)
            val writes = collecting(settings.settingsFlow.map { it?.aiBaseUrl })
            for (typed in listOf("http://192", "http://192.168.1.20:11434")) {
                model.type(TextSetting.AI_BASE_URL, typed)
                runCurrent()
                model.state.value.snapshot.aiBaseUrl shouldBe typed
            }
            settings.settingsFlow.value?.aiBaseUrl shouldBe ""

            advanceUntilIdle()

            writes shouldContainExactly listOf("", "http://192.168.1.20:11434")
            model.state.value.snapshot.aiBaseUrl shouldBe "http://192.168.1.20:11434"
        }

    /** Leaving a field, or the screen, saves what was typed in it at once. */
    @Test
    fun anEndpointFieldIsSavedWhenLeft() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = settingsVm(settings = settings)
            val store = ViewModelStore().apply { put("settings", model) }

            model.type(TextSetting.AI_MODEL, "qwen3:8b")
            model.doneTyping(TextSetting.AI_MODEL)
            runCurrent()
            settings.settingsFlow.value?.aiModel shouldBe "qwen3:8b"

            model.type(TextSetting.AI_API_KEY, "sk-secret")
            runCurrent()
            store.clear()
            advanceUntilIdle()
            settings.settingsFlow.value?.aiApiKey shouldBe "sk-secret"
        }

    /** A base URL typed for one provider is not saved for the next. */
    @Test
    fun switchingProviderDropsATypedBaseUrl() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = settingsVm(settings = settings)
            collecting(model.state)
            model.setAiProvider(AiProvider.CUSTOM)
            advanceUntilIdle()

            model.type(TextSetting.AI_BASE_URL, "http://192.168.1.20:11434")
            model.setAiProvider(AiProvider.OLLAMA)
            advanceUntilIdle()

            settings.settingsFlow.value?.aiBaseUrl shouldBe ""
            model.state.value.snapshot.aiBaseUrl shouldBe ""
        }

    @Test
    fun editClampsOutOfRangeValuesInsideTheRepository() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = settingsVm(settings = settings)
            // The caller does not clamp: the repository coerces into AiTagging.RANGE / NEW_TAGS_RANGE.
            model.edit { it.copy(aiMaxTagsPerDoc = 999, aiMaxNewTagsPerDoc = -5) }
            advanceUntilIdle()
            settings.settingsFlow.value?.aiMaxTagsPerDoc shouldBe AiTagging.MAX_TAGS_PER_DOC
            settings.settingsFlow.value?.aiMaxNewTagsPerDoc shouldBe AiTagging.MIN_NEW_TAGS_PER_DOC
        }

    @Test
    fun switchingProviderClearsFetchedModelList() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = settingsVm(settings = settings)
            model.setAiProvider(AiProvider.OLLAMA)
            advanceUntilIdle()
            model.aiModels.value.models shouldBe emptyList()
            model.aiModels.value.error shouldBe null
            model.aiModels.value.loading shouldBe false
        }

    @Test
    fun switchingProviderDropsTheBaseUrlOfThePreviousOne() =
        runTest(dispatcher) {
            val settings =
                FakeSettingsRepository().apply {
                    updateSettings { it.copy(aiProvider = AiProvider.OLLAMA, aiBaseUrl = "http://192.168.1.20:11434") }
                }
            val model = settingsVm(settings = settings)

            model.setAiProvider(AiProvider.OLLAMA)
            advanceUntilIdle()
            settings.settingsFlow.value?.aiBaseUrl shouldBe "http://192.168.1.20:11434"

            model.setAiProvider(AiProvider.ANTHROPIC)
            advanceUntilIdle()
            settings.settingsFlow.value?.aiProvider shouldBe AiProvider.ANTHROPIC
            settings.settingsFlow.value?.aiBaseUrl shouldBe ""
        }
}
