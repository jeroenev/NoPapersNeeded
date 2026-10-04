package dev.npn.core.ai

import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.ollama.client.OllamaParams
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class AiRuntimeTest {
    @Test
    fun normalizeBaseUrlStripsTrailingSlash() {
        AiProviderClients.normalizeBaseUrl("https://api.openai.com/") shouldBe "https://api.openai.com"
    }

    @Test
    fun normalizeBaseUrlStripsTrailingV1() {
        AiProviderClients.normalizeBaseUrl("https://api.openai.com/v1") shouldBe "https://api.openai.com"
    }

    @Test
    fun normalizeBaseUrlStripsV1WithTrailingSlash() {
        AiProviderClients.normalizeBaseUrl("https://api.openai.com/v1/") shouldBe "https://api.openai.com"
    }

    @Test
    fun normalizeBaseUrlLeavesPlainHostUntouched() {
        AiProviderClients.normalizeBaseUrl("http://localhost:11434") shouldBe "http://localhost:11434"
    }

    @Test
    fun normalizeBaseUrlTrimsWhitespace() {
        AiProviderClients.normalizeBaseUrl("  https://api.openai.com/v1  ") shouldBe "https://api.openai.com"
    }

    @Test
    fun normalizeBaseUrlKeepsMidPathV1() {
        AiProviderClients.normalizeBaseUrl("https://x/v1/proxy") shouldBe "https://x/v1/proxy"
    }

    @Test
    fun normalizeBaseUrlStripsFullChatCompletionsEndpoint() {
        AiProviderClients.normalizeBaseUrl("https://opencode.ai/zen/go/v1/chat/completions") shouldBe
            "https://opencode.ai/zen/go"
    }

    @Test
    fun normalizeBaseUrlStripsChatCompletionsWithoutV1() {
        AiProviderClients.normalizeBaseUrl("https://gateway.example/api/chat/completions/") shouldBe
            "https://gateway.example/api"
    }

    @Test
    fun normalizeBaseUrlKeepsGatewayPathPrefix() {
        AiProviderClients.normalizeBaseUrl("https://opencode.ai/zen/go") shouldBe "https://opencode.ai/zen/go"
    }

    @Test
    fun defaultReasoningMapsToNoOverrideForEveryProvider() {
        AiProvider.entries.forEach { provider ->
            AiRuntime.reasoningParams(provider, "some-model", AiReasoningLevel.DEFAULT, toolLoop = false) shouldBe null
            AiRuntime.reasoningParams(provider, "some-model", AiReasoningLevel.DEFAULT, toolLoop = true) shouldBe null
        }
    }

    @Test
    fun openAiFamilyMapsToReasoningEffort() {
        listOf(AiProvider.OPENAI, AiProvider.CUSTOM, AiProvider.OPENCODE_GO).forEach { provider ->
            val params = AiRuntime.reasoningParams(provider, "some-model", AiReasoningLevel.LOW, toolLoop = true)
            params.shouldBeInstanceOf<OpenAIChatParams>().reasoningEffort shouldBe ReasoningEffort.LOW
            val none = AiRuntime.reasoningParams(provider, "some-model", AiReasoningLevel.NONE, toolLoop = false)
            none.shouldBeInstanceOf<OpenAIChatParams>().reasoningEffort shouldBe ReasoningEffort.NONE
        }
    }

    @Test
    fun anthropicSuggestionReasoningEnablesThinkingWithHeadroom() {
        val params =
            AiRuntime.reasoningParams(
                AiProvider.ANTHROPIC,
                "some-model",
                AiReasoningLevel.HIGH,
                toolLoop = false,
            )
        val anthropic = params.shouldBeInstanceOf<AnthropicParams>()
        anthropic.thinking.shouldBeInstanceOf<AnthropicThinking.Enabled>().budgetTokens shouldBe 16_384
        anthropic.maxTokens shouldBe 16_384 + 4_096
    }

    @Test
    fun anthropicChatSkipsEnablingThinkingButCanDisableIt() {
        // Enabling thinking in the tool loop is unsupported: Anthropic requires emitted thinking
        // blocks to be replayed verbatim with tool results, and the chat loop replays plain text.
        AiRuntime.reasoningParams(AiProvider.ANTHROPIC, "some-model", AiReasoningLevel.MEDIUM, toolLoop = true) shouldBe
            null
        val none = AiRuntime.reasoningParams(AiProvider.ANTHROPIC, "some-model", AiReasoningLevel.NONE, toolLoop = true)
        none.shouldBeInstanceOf<AnthropicParams>().thinking.shouldBeInstanceOf<AnthropicThinking.Disabled>()
    }

    @Test
    fun googleMapsToThinkingBudget() {
        val medium =
            AiRuntime.reasoningParams(
                AiProvider.GOOGLE,
                "some-model",
                AiReasoningLevel.MEDIUM,
                toolLoop = true,
            )
        medium.shouldBeInstanceOf<GoogleParams>().thinkingConfig?.thinkingBudget shouldBe 8_192
        val none = AiRuntime.reasoningParams(AiProvider.GOOGLE, "some-model", AiReasoningLevel.NONE, toolLoop = false)
        none.shouldBeInstanceOf<GoogleParams>().thinkingConfig?.thinkingBudget shouldBe 0
    }

    @Test
    fun ollamaMapsToThinkFlag() {
        AiRuntime
            .reasoningParams(AiProvider.OLLAMA, "some-model", AiReasoningLevel.NONE, toolLoop = false)
            .shouldBeInstanceOf<OllamaParams>()
            .think shouldBe false
        AiRuntime
            .reasoningParams(AiProvider.OLLAMA, "some-model", AiReasoningLevel.HIGH, toolLoop = true)
            .shouldBeInstanceOf<OllamaParams>()
            .think shouldBe true
    }

    @Test
    fun providersWithoutReasoningKnobIgnoreTheLevel() {
        listOf(AiProvider.OPENROUTER, AiProvider.DEEPSEEK, AiProvider.MISTRAL).forEach { provider ->
            AiRuntime.reasoningParams(provider, "some-model", AiReasoningLevel.HIGH, toolLoop = false) shouldBe null
        }
    }

    @Test
    fun buildWiresEveryProviderToItsOwnKoogClient() {
        AiProvider.entries.forEach { provider ->
            val runtime =
                AiRuntime.build(
                    AiConfig(
                        enabled = true,
                        provider = provider,
                        model = "some-model",
                        apiKey = "key",
                        baseUrl = if (provider == AiProvider.CUSTOM) "http://localhost:1" else "",
                    ),
                )
            try {
                runtime.model.id shouldBe "some-model"
                runtime.model.provider.id shouldBe
                    when (provider) {
                        AiProvider.OPENAI, AiProvider.CUSTOM, AiProvider.OPENCODE_GO -> "openai"
                        AiProvider.ANTHROPIC -> "anthropic"
                        AiProvider.GOOGLE -> "google"
                        AiProvider.MISTRAL -> "mistralai"
                        AiProvider.DEEPSEEK -> "deepseek"
                        AiProvider.OPENROUTER -> "openrouter"
                        AiProvider.OLLAMA -> "ollama"
                    }
            } finally {
                runtime.close()
            }
        }
    }
}
