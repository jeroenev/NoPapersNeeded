package dev.npn.core.ai

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class AiRuntimeTest {

    @Test
    fun normalizeBaseUrlStripsTrailingSlash() {
        AiRuntime.normalizeBaseUrl("https://api.openai.com/") shouldBe "https://api.openai.com"
    }

    @Test
    fun normalizeBaseUrlStripsTrailingV1() {
        AiRuntime.normalizeBaseUrl("https://api.openai.com/v1") shouldBe "https://api.openai.com"
    }

    @Test
    fun normalizeBaseUrlStripsV1WithTrailingSlash() {
        AiRuntime.normalizeBaseUrl("https://api.openai.com/v1/") shouldBe "https://api.openai.com"
    }

    @Test
    fun normalizeBaseUrlLeavesPlainHostUntouched() {
        AiRuntime.normalizeBaseUrl("http://localhost:11434") shouldBe "http://localhost:11434"
    }

    @Test
    fun normalizeBaseUrlTrimsWhitespace() {
        AiRuntime.normalizeBaseUrl("  https://api.openai.com/v1  ") shouldBe "https://api.openai.com"
    }

    @Test
    fun normalizeBaseUrlKeepsMidPathV1() {
        AiRuntime.normalizeBaseUrl("https://x/v1/proxy") shouldBe "https://x/v1/proxy"
    }

    @Test
    fun normalizeBaseUrlStripsFullChatCompletionsEndpoint() {
        AiRuntime.normalizeBaseUrl("https://opencode.ai/zen/go/v1/chat/completions") shouldBe
            "https://opencode.ai/zen/go"
    }

    @Test
    fun normalizeBaseUrlStripsChatCompletionsWithoutV1() {
        AiRuntime.normalizeBaseUrl("https://gateway.example/api/chat/completions/") shouldBe
            "https://gateway.example/api"
    }

    @Test
    fun normalizeBaseUrlKeepsGatewayPathPrefix() {
        AiRuntime.normalizeBaseUrl("https://opencode.ai/zen/go") shouldBe "https://opencode.ai/zen/go"
    }

    @Test
    fun configuredFalseWhenDisabled() {
        AiConfig(enabled = false, baseUrl = "https://x", model = "m").configured shouldBe false
    }

    @Test
    fun configuredFalseWhenBlankBaseUrl() {
        AiConfig(enabled = true, baseUrl = "", model = "m").configured shouldBe false
    }

    @Test
    fun configuredFalseWhenBlankModel() {
        AiConfig(enabled = true, baseUrl = "https://x", model = "").configured shouldBe false
    }

    @Test
    fun configuredTrueWithBlankApiKey() {
        AiConfig(enabled = true, baseUrl = "https://x", model = "m", apiKey = "").configured shouldBe true
    }
}
