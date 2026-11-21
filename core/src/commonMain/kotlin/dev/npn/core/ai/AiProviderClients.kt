package dev.npn.core.ai

import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.executor.clients.deepseek.DeepSeekClientSettings
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.clients.google.GoogleClientSettings
import ai.koog.prompt.executor.clients.google.GoogleLLMClient
import ai.koog.prompt.executor.clients.google.GoogleParams
import ai.koog.prompt.executor.clients.google.models.GoogleThinkingConfig
import ai.koog.prompt.executor.clients.mistralai.MistralAIClientSettings
import ai.koog.prompt.executor.clients.mistralai.MistralAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIChatParams
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams
import io.ktor.client.HttpClient

/**
 * Everything that differs by [AiProvider]: the provider the Koog client registers under, what the
 * configured model is declared to do, the client itself, and how a call to it is shaped.
 *
 * @param client builds the client for an endpoint. It is handed the API key and the base URL
 *  [AiConfig.effectiveBaseUrl] resolved, never the raw setting, so a provider without an editable
 *  URL always talks to its [AiProvider.defaultBaseUrl] and no client falls back to a default of
 *  Koog's own.
 * @param reasoning the params for one [AiReasoningLevel] other than [AiReasoningLevel.DEFAULT] on a
 *  model, in a tool loop or not; null leaves the endpoint's default (see [AiRuntime.reasoningParams]).
 * @param ocrParams the params of one page transcription (see [AiRuntime.ocrParams]).
 * @param transport derives the Ktor client the provider's requests go through from the runtime's
 *  base one, for a wire requirement of the provider's own; never in place of what the base has.
 * @param listModels the ids of the models the endpoint offers, asked of a client [client] built.
 */
internal class ProviderClientFactory(
    val provider: LLMProvider,
    val capabilities: (AiConfig) -> List<LLMCapability>,
    val reasoning: (model: String, level: AiReasoningLevel, toolLoop: Boolean) -> LLMParams? = { _, _, _ -> null },
    val ocrParams: () -> LLMParams = { LLMParams() },
    val transport: (HttpClient) -> HttpClient = { it },
    val listModels: suspend (LLMClient) -> List<String> = { built -> built.models().map { it.id } },
    val client: (apiKey: String, baseUrl: String, model: LLModel, http: KoogHttpClient.Factory) -> LLMClient,
)

/** The [ProviderClientFactory] of every [AiProvider], the one place a provider's behaviour is told. */
internal object AiProviderClients {
    fun of(provider: AiProvider): ProviderClientFactory =
        when (provider) {
            AiProvider.OPENAI, AiProvider.CUSTOM -> openAi
            AiProvider.OPENCODE_GO -> openCodeGo
            AiProvider.ANTHROPIC -> anthropic
            AiProvider.GOOGLE -> google
            AiProvider.MISTRAL -> mistral
            AiProvider.DEEPSEEK -> deepSeek
            AiProvider.OPENROUTER -> openRouter
            AiProvider.OLLAMA -> ollama
        }

    /**
     * Thinking-token budgets for providers configured through a budget rather than an effort label
     * (Anthropic, Google). Anthropic requires at least 1024.
     */
    private const val THINKING_BUDGET_LOW = 2_048
    private const val THINKING_BUDGET_MEDIUM = 8_192
    private const val THINKING_BUDGET_HIGH = 16_384

    /**
     * Anthropic requires `max_tokens` to exceed the thinking budget (Koog's default is a flat 2048),
     * so thinking calls raise it to budget + this answer headroom.
     */
    private const val ANTHROPIC_ANSWER_HEADROOM = 4_096

    /**
     * Output budget for one page transcription ([AiFacadeImpl.ocrEngine]). Comfortably more than any
     * single scanned page holds, and the ceiling every Claude 3+ model accepts — raising it further
     * would be an API error on the older ones, and costs nothing to leave here since output tokens
     * are billed as produced, not as reserved.
     */
    private const val OCR_ANSWER_MAX_TOKENS = 4_096

    /**
     * OpenAI, and the servers compatible with it. [TolerantOpenAILLMClient] subclasses
     * OpenAILLMClient to skip non-standard trailing SSE chunks (cost/usage frames) that would
     * otherwise abort streaming after real frames but before Koog's terminal End frame. See
     * TolerantOpenAILLMClient for the Koog seam facts.
     *
     * Reasoning is a `reasoning_effort` label. Ignored by non-reasoning models; unsupported labels
     * (e.g. `none` on older models) surface as an endpoint error the user can fix by picking
     * Default. The params TYPE has to agree with the endpoint the model is called on
     * ([responsesApi]): Koog's `determineParams` treats explicit `OpenAIChatParams` as a demand for
     * chat/completions and fails the call when the model does not declare that endpoint, so a
     * Responses-API model needs `OpenAIResponsesParams`.
     */
    private fun openAiCompatible(
        responsesApi: (model: String) -> Boolean = { false },
        transport: (HttpClient) -> HttpClient = { it },
    ) = ProviderClientFactory(
        LLMProvider.OpenAI,
        capabilities = { config -> openAiCapabilities(responsesApi(config.model)) },
        reasoning = { model, level, _ ->
            if (responsesApi(model)) {
                OpenAIResponsesParams(reasoning = ReasoningConfig(effort = level.toReasoningEffort()))
            } else {
                OpenAIChatParams(reasoningEffort = level.toReasoningEffort())
            }
        },
        transport = transport,
    ) { apiKey, baseUrl, _, http ->
        TolerantOpenAILLMClient(
            apiKey = apiKey.ifBlank { "unused" },
            settings = OpenAIClientSettings(baseUrl = normalizeBaseUrl(baseUrl)),
            httpClientFactory = http,
        )
    }

    /**
     * [AiProvider.OPENAI] serves both APIs, and [AiProvider.CUSTOM] servers implement
     * chat/completions while often naming a model `gpt-...`: both stay on chat/completions.
     */
    private val openAi = openAiCompatible()

    /**
     * The OpenCode Go gateway serves its OpenAI-family models only on the Responses API and every
     * other model only on chat/completions, and answers HTTP 500 on the wrong one (models.dev
     * encodes the same split for `opencode-go`). It also rejects a request without a session header
     * ([withOpenCodeSession]).
     */
    private val openCodeGo =
        openAiCompatible(
            responsesApi = ::isOpenAiFamilyModel,
            transport = { it.withOpenCodeSession() },
        )

    /**
     * A model id is free text, so this is a heuristic that falls back to chat/completions for an id
     * it does not recognise.
     */
    private fun isOpenAiFamilyModel(model: String): Boolean {
        // Gateways sometimes qualify ids with the upstream vendor ("openai/gpt-5.1").
        val id = model.trim().lowercase().substringAfterLast('/')
        return OPENAI_FAMILY_MODEL_ID.containsMatchIn(id)
    }

    /** `gpt-4o`, `gpt-5.6-luna`, `o3-mini`, `gpt-5.1-codex-max`, ... */
    private val OPENAI_FAMILY_MODEL_ID = Regex("^(gpt-|o[134](-|$))|codex")

    /**
     * The Anthropic client resolves the wire model name through `modelVersionsMap` and throws
     * "Unsupported model" for anything absent, so the user's model must be mapped explicitly (the
     * default map only covers Koog's predefined AnthropicModels).
     *
     * Reasoning is a thinking budget, plus a raised `max_tokens` (the API requires
     * `max_tokens > budget_tokens`). ENABLING thinking is skipped in the tool loop: Anthropic
     * requires the emitted thinking blocks to be replayed verbatim with tool results, and the chat
     * loop replays turns as plain text. Disabling is safe.
     *
     * The only provider whose page transcription gets params of its own: thinking off, and a
     * `max_tokens` above Koog's 2048 default.
     */
    private val anthropic =
        ProviderClientFactory(
            LLMProvider.Anthropic,
            capabilities = { STANDARD_CAPABILITIES },
            reasoning = { _, level, toolLoop -> anthropicReasoningParams(level, toolLoop) },
            ocrParams = {
                AnthropicParams(
                    maxTokens = OCR_ANSWER_MAX_TOKENS,
                    thinking = AnthropicThinking.Disabled(),
                )
            },
        ) { apiKey, baseUrl, model, http ->
            AnthropicLLMClient(
                apiKey,
                AnthropicClientSettings(modelVersionsMap = mapOf(model to model.id), baseUrl = trimBaseUrl(baseUrl)),
                http,
            )
        }

    /**
     * Reasoning is a thinking budget; 0 disables (supported by Flash models). Tool-call replay
     * without signatures is covered by the Koog client's fallback thought signature.
     */
    private val google =
        ProviderClientFactory(
            LLMProvider.Google,
            capabilities = { STANDARD_CAPABILITIES },
            reasoning = { _, level, _ ->
                GoogleParams(thinkingConfig = GoogleThinkingConfig(thinkingBudget = level.toBudget()))
            },
        ) { apiKey, baseUrl, _, http ->
            GoogleLLMClient(apiKey, GoogleClientSettings(baseUrl = trimBaseUrl(baseUrl)), http)
        }

    // Mistral, DeepSeek and OpenRouter: Koog 1.3.0 exposes no reasoning knob for these clients, so
    // the level is ignored (endpoint default).

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

    /**
     * Reasoning is only an on/off `think` flag, and Ollama refuses `think: true` (HTTP 400, "does not
     * support thinking") for a model that cannot think, which most local models cannot. So only
     * NONE, which every model accepts, and MEDIUM and HIGH, which ask for thinking outright, set it;
     * LOW, the AI-edit default, keeps the model's own behaviour, so the default never breaks every
     * edit call on such a model. The local models are listed through Ollama's own management API
     * rather than `LLMClientAPI.models()`, on the [OllamaClient] this factory builds.
     */
    private val ollama =
        ProviderClientFactory(
            LLMProvider.Ollama,
            capabilities = { OLLAMA_CAPABILITIES },
            reasoning = { _, level, _ ->
                when (level) {
                    AiReasoningLevel.NONE -> OllamaParams(think = false)
                    AiReasoningLevel.MEDIUM, AiReasoningLevel.HIGH -> OllamaParams(think = true)
                    AiReasoningLevel.DEFAULT, AiReasoningLevel.LOW -> null
                }
            },
            listModels = { built -> (built as OllamaClient).getModels().map { it.name } },
        ) { _, baseUrl, _, http ->
            OllamaClient(httpClientFactory = http, baseUrl = trimBaseUrl(baseUrl))
        }

    private fun anthropicReasoningParams(
        level: AiReasoningLevel,
        toolLoop: Boolean,
    ): LLMParams? {
        if (level == AiReasoningLevel.NONE) return AnthropicParams(thinking = AnthropicThinking.Disabled())
        if (toolLoop) return null
        val budget = level.toBudget()
        return AnthropicParams(
            maxTokens = budget + ANTHROPIC_ANSWER_HEADROOM,
            thinking = AnthropicThinking.Enabled(budgetTokens = budget),
        )
    }

    private fun AiReasoningLevel.toReasoningEffort(): ReasoningEffort =
        when (this) {
            AiReasoningLevel.DEFAULT -> error("DEFAULT never maps to params")
            AiReasoningLevel.NONE -> ReasoningEffort.NONE
            AiReasoningLevel.LOW -> ReasoningEffort.LOW
            AiReasoningLevel.MEDIUM -> ReasoningEffort.MEDIUM
            AiReasoningLevel.HIGH -> ReasoningEffort.HIGH
        }

    private fun AiReasoningLevel.toBudget(): Int =
        when (this) {
            AiReasoningLevel.DEFAULT -> error("DEFAULT never maps to params")
            AiReasoningLevel.NONE -> 0
            AiReasoningLevel.LOW -> THINKING_BUDGET_LOW
            AiReasoningLevel.MEDIUM -> THINKING_BUDGET_MEDIUM
            AiReasoningLevel.HIGH -> THINKING_BUDGET_HIGH
        }

    /**
     * Without an OpenAIEndpoint capability the client cannot pick a request shape and fails with
     * "Cannot determine proper LLM params". Exactly one is declared, never both: given a choice
     * Koog's `determineParams` always prefers chat/completions, which is the wrong half of the
     * OpenCode Go split (see [openCodeGo]). Compatible third-party servers implement
     * chat/completions, not the Responses API, so that stays the default.
     */
    private fun openAiCapabilities(responsesApi: Boolean): List<LLMCapability> =
        listOf(
            if (responsesApi) {
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
