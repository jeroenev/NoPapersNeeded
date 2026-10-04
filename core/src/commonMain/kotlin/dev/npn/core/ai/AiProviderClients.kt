package dev.npn.core.ai

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.mistralai.MistralAIClientSettings
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * How one [AiProvider] becomes a Koog client: the provider the client registers under, what the
 * configured model is declared to do, and the client itself.
 *
 * @param client builds the client for an endpoint. It is handed the API key and the base URL
 *  [AiConfig.effectiveBaseUrl] resolved, never the raw setting, so a provider without an editable
 *  URL always talks to its [AiProvider.defaultBaseUrl] and no client falls back to a default of
 *  Koog's own.
 */
internal class ProviderClientFactory(
    val provider: LLMProvider,
    val capabilities: (AiConfig) -> List<LLMCapability>,
    val client: (apiKey: String, baseUrl: String, model: LLModel, http: KoogHttpClient.Factory) -> LLMClient,
)

/** The [ProviderClientFactory] of every [AiProvider]; [AiRuntime.build] is their one caller. */
internal object AiProviderClients {
    fun of(provider: AiProvider): ProviderClientFactory =
        when (provider) {
            AiProvider.OPENAI, AiProvider.CUSTOM, AiProvider.OPENCODE_GO -> openAiCompatible
            AiProvider.ANTHROPIC -> anthropic
            AiProvider.GOOGLE -> google
            AiProvider.MISTRAL -> mistral
            AiProvider.DEEPSEEK -> deepSeek
            AiProvider.OPENROUTER -> openRouter
            AiProvider.OLLAMA -> ollama
        }

    /**
     * [TolerantOpenAILLMClient] subclasses OpenAILLMClient to skip non-standard trailing SSE chunks
     * (cost/usage frames) that would otherwise abort streaming after real frames but before Koog's
     * terminal End frame. See TolerantOpenAILLMClient for the Koog seam facts.
     */
    private val openAiCompatible =
        ProviderClientFactory(LLMProvider.OpenAI, ::openAiCapabilities) { apiKey, baseUrl, _, http ->
            TolerantOpenAILLMClient(
                apiKey = apiKey.ifBlank { "unused" },
                settings = OpenAIClientSettings(baseUrl = normalizeBaseUrl(baseUrl)),
                httpClientFactory = http,
            )
        }

    /**
     * The Anthropic client resolves the wire model name through `modelVersionsMap` and throws
     * "Unsupported model" for anything absent, so the user's model must be mapped explicitly (the
     * default map only covers Koog's predefined AnthropicModels).
     */
    private val anthropic =
        ProviderClientFactory(LLMProvider.Anthropic, { STANDARD_CAPABILITIES }) { apiKey, baseUrl, model, http ->
            AnthropicLLMClient(
                apiKey,
                AnthropicClientSettings(modelVersionsMap = mapOf(model to model.id), baseUrl = trimBaseUrl(baseUrl)),
                http,
            )
        }

    private val google =
        ProviderClientFactory(LLMProvider.Google, { STANDARD_CAPABILITIES }) { apiKey, baseUrl, _, http ->
            GoogleLLMClient(apiKey, GoogleClientSettings(baseUrl = trimBaseUrl(baseUrl)), http)
        }

    private val mistral =
        ProviderClientFactory(LLMProvider.MistralAI, { STANDARD_CAPABILITIES }) { apiKey, baseUrl, _, http ->
            MistralAILLMClient(apiKey, MistralAIClientSettings(baseUrl = trimBaseUrl(baseUrl)), http)
        }

    private val deepSeek =
        ProviderClientFactory(LLMProvider.DeepSeek, { STANDARD_CAPABILITIES }) { apiKey, baseUrl, _, http ->
            DeepSeekLLMClient(apiKey, DeepSeekClientSettings(baseUrl = trimBaseUrl(baseUrl)), http)
        }

    private val openRouter =
        ProviderClientFactory(LLMProvider.OpenRouter, { STANDARD_CAPABILITIES }) { apiKey, baseUrl, _, http ->
            OpenRouterLLMClient(apiKey, OpenRouterClientSettings(baseUrl = trimBaseUrl(baseUrl)), http)
        }

    private val ollama =
        ProviderClientFactory(LLMProvider.Ollama, { OLLAMA_CAPABILITIES }) { _, baseUrl, _, http ->
            OllamaClient(httpClientFactory = http, baseUrl = trimBaseUrl(baseUrl))
        }

    /**
     * Without an OpenAIEndpoint capability the client cannot pick a request shape and fails with
     * "Cannot determine proper LLM params". Exactly one is declared, never both: given a choice
     * Koog's `determineParams` always prefers chat/completions, which is the wrong half of the
     * OpenCode Go split (see [AiRuntime.usesResponsesApi]). Compatible third-party servers implement
     * chat/completions, not the Responses API, so that stays the default.
     */
    private fun openAiCapabilities(config: AiConfig): List<LLMCapability> =
        listOf(
            if (AiRuntime.usesResponsesApi(config.provider, config.model)) {
                LLMCapability.OpenAIEndpoint.Responses
            } else {
                LLMCapability.OpenAIEndpoint.Completions
            },
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Vision.Image,
        )

    /**
     * Capabilities for the native (non-OpenAI-shaped) clients. [LLMCapability.Thinking] is
     * included so the Anthropic/Google clients accept reasoning content on replayed assistant
     * messages (Google injects its fallback thought signature only for Thinking-capable models).
     *
     * [LLMCapability.Vision.Image] is included because page-image OCR ([AiFacadeImpl.ocrEngine]) sends image
     * parts, and the clients reject an image for a model that does not declare it. Like Tools
     * and Thinking above, this is a claim rather than a fact: the model id is free text the
     * user typed, so nothing here can know what it really supports. A model without vision
     * fails the OCR call at the endpoint with a message the user can act on, which is strictly
     * better than the client refusing to send it and every model needing a capability probe.
     */
    private val STANDARD_CAPABILITIES: List<LLMCapability> =
        listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.ToolChoice,
            LLMCapability.Thinking,
            LLMCapability.Vision.Image,
        )

    private val OLLAMA_CAPABILITIES: List<LLMCapability> =
        listOf(
            LLMCapability.Completion,
            LLMCapability.Temperature,
            LLMCapability.Tools,
            LLMCapability.Vision.Image,
        )

    /**
     * Reduce a pasted endpoint to the server root. Koog appends `v1/chat/completions`
     * itself, so the full endpoint URL (as printed by most provider docs) and a bare
     * `/v1` suffix both have to be stripped or the path doubles up.
     */
    internal fun normalizeBaseUrl(raw: String): String =
        raw
            .trim()
            .trimEnd('/')
            .removeSuffix("/chat/completions")
            .trimEnd('/')
            .removeSuffix("/v1")

    /** Trim variant for clients with provider-specific request paths (no `/v1` stripping). */
    internal fun trimBaseUrl(raw: String): String = raw.trim().trimEnd('/')
}
