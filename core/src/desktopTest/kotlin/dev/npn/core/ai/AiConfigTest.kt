package dev.npn.core.ai

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
        AiConfig(provider = AiProvider.OPENROUTER, baseUrl = "https://p").effectiveBaseUrl shouldBe "https://p"
    }

    @Test
    fun customProviderStaysLastInThePicker() {
        // The settings dropdown renders entries in declaration order; fully manual setup must
        // remain the final option.
        AiProvider.entries.last() shouldBe AiProvider.CUSTOM
    }

    private fun custom(
        enabled: Boolean = true,
        baseUrl: String = "https://x",
        apiKey: String = "",
        model: String = "m",
    ) = AiConfig(enabled = enabled, provider = AiProvider.CUSTOM, baseUrl = baseUrl, apiKey = apiKey, model = model)
}
