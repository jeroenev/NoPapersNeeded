package dev.npn.core.ai

import dev.npn.core.settings.SettingsSnapshot
import dev.npn.core.settings.toAiConfig
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AiConfigTest {
    @Test
    fun configuredFalseWhenDisabled() {
        custom(enabled = false).configured shouldBe false
    }

    @Test
    fun configuredFalseWhenCustomLacksBaseUrl() {
        custom(baseUrl = "").configured shouldBe false
    }

    @Test
    fun configuredFalseWhenBlankModel() {
        custom(model = "").configured shouldBe false
    }

    @Test
    fun configuredTrueForCustomWithBlankApiKey() {
        custom(apiKey = "").configured shouldBe true
    }

    @Test
    fun configuredFalseForHostedProviderWithoutApiKey() {
        AiConfig(enabled = true, provider = AiProvider.OPENAI, model = "m").configured shouldBe false
    }

    @Test
    fun configuredTrueForHostedProviderWithApiKey() {
        AiConfig(enabled = true, provider = AiProvider.ANTHROPIC, model = "m", apiKey = "k").configured shouldBe true
    }

    @Test
    fun configuredTrueForOllamaWithoutKeyOrBaseUrl() {
        AiConfig(enabled = true, provider = AiProvider.OLLAMA, model = "qwen3:8b").configured shouldBe true
    }

    @Test
    fun effectiveBaseUrlFallsBackToProviderDefault() {
        AiConfig(provider = AiProvider.OPENROUTER).effectiveBaseUrl shouldBe "https://openrouter.ai"
        AiConfig(provider = AiProvider.OLLAMA, baseUrl = "http://nas:11434").effectiveBaseUrl shouldBe
            "http://nas:11434"
    }

    @Test
    fun aFixedUrlProviderIgnoresAStoredBaseUrl() {
        // The settings screen hides the field for these, so a stored value is a leftover.
        AiConfig(provider = AiProvider.OPENROUTER, baseUrl = "http://nas:11434").effectiveBaseUrl shouldBe
            "https://openrouter.ai"
    }

    @Test
    fun customProviderStaysLastInThePicker() {
        // The settings dropdown renders entries in declaration order; fully manual setup must
        // remain the final option.
        AiProvider.entries.last() shouldBe AiProvider.CUSTOM
    }

    @Test
    fun appLanguageFallsBackToTheSystemLanguageOnlyWhenUnset() {
        // Null in settings means "follow the system", which is still a language a prompt can use.
        snapshot(appLanguage = null).toAiConfig(systemLanguage = "fr").appLanguage shouldBe "fr"
        // An explicit choice wins, so a background job without the UI still prompts in it.
        snapshot(appLanguage = "nl").toAiConfig(systemLanguage = "fr").appLanguage shouldBe "nl"
        snapshot(appLanguage = null).toAiConfig().appLanguage shouldBe null
    }

    private fun snapshot(appLanguage: String?) =
        SettingsSnapshot(
            installationId = "i",
            appLanguage = appLanguage,
            aiEnabled = true,
            aiProvider = AiProvider.CUSTOM,
            aiBaseUrl = "https://x",
            aiModel = "m",
        )

    private fun custom(
        enabled: Boolean = true,
        baseUrl: String = "https://x",
        apiKey: String = "",
        model: String = "m",
    ) = AiConfig(enabled = enabled, provider = AiProvider.CUSTOM, baseUrl = baseUrl, apiKey = apiKey, model = model)
}
