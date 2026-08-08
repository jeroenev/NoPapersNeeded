package dev.npn.core.ai

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
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
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openrouter.OpenRouterClientSettings
import ai.koog.prompt.executor.clients.openrouter.OpenRouterLLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.executor.ollama.client.OllamaClient
import ai.koog.prompt.executor.ollama.client.OllamaParams
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.params.LLMParams

/**
 * Koog executor + model pair built from one [AiConfig]. Rebuilt only when the config
 * changes (the facade caches it), so the underlying HTTP client is reused across calls.
 */
internal class AiRuntime(
    val executor: PromptExecutor,
    val model: LLModel,
    private val client: LLMClient,
) {
    /**
     * Ids of the models the configured endpoint offers, for the settings model picker.
     * Ollama exposes its local model list through a dedicated management API instead of
     * `LLMClientAPI.models()`, so it is special-cased.
     */
    suspend fun listModelIds(): List<String> =
        when (client) {
            is OllamaClient -> client.getModels().map { it.name }
            else -> client.models().map { it.id }
        }.sorted()

    /** Release the underlying HTTP client. Call once the runtime is no longer cached. */
    fun close() {
        client.close()
    }

    companion object {
        private const val DEFAULT_CONTEXT_LENGTH = 128_000L

        /**
         * Thinking-token budgets for providers configured through a budget rather than an
         * effort label (Anthropic, Google). Anthropic requires at least 1024.
         */
        private const val THINKING_BUDGET_LOW = 2_048
        private const val THINKING_BUDGET_MEDIUM = 8_192
        private const val THINKING_BUDGET_HIGH = 16_384

        /**
         * Anthropic requires `max_tokens` to exceed the thinking budget (Koog's default is a
         * flat 2048), so thinking calls raise it to budget + this answer headroom.
         */
        private const val ANTHROPIC_ANSWER_HEADROOM = 4_096

        fun build(config: AiConfig): AiRuntime {
            val httpFactory = KtorKoogHttpClient.Factory()
            return when (config.provider) {
                AiProvider.OPENAI, AiProvider.CUSTOM -> openAiRuntime(config, httpFactory)
                AiProvider.ANTHROPIC -> anthropicRuntime(config, httpFactory)
                AiProvider.GOOGLE -> googleRuntime(config, httpFactory)
                AiProvider.MISTRAL -> mistralRuntime(config, httpFactory)
                AiProvider.DEEPSEEK -> deepSeekRuntime(config, httpFactory)
                AiProvider.OPENROUTER -> openRouterRuntime(config, httpFactory)
                AiProvider.OLLAMA -> ollamaRuntime(config, httpFactory)
            }
        }

        /**
         * Params for the one-shot AI-edit/suggestion prompts, from [AiConfig.reasoningEdit].
         * These run without tools, so every provider mapping is safe to apply.
         */
        fun suggestionParams(config: AiConfig): LLMParams =
            reasoningParams(config.provider, config.reasoningEdit, toolLoop = false) ?: LLMParams()

        /**
         * Params for the chat assistant, from [AiConfig.reasoningAssistant]; null keeps the
         * prompt's defaults. Chat runs a tool loop, which restricts what can be applied (see
         * [reasoningParams]).
         */
        fun chatParams(config: AiConfig): LLMParams? =
            reasoningParams(config.provider, config.reasoningAssistant, toolLoop = true)

        /**
         * Map one user-facing [AiReasoningLevel] onto provider-specific Koog params; null means
         * "no override" (either [AiReasoningLevel.DEFAULT] or an unsupported combination).
         *
         * Provider notes:
         * - OpenAI/custom: `reasoning_effort` label. Ignored by non-reasoning models; unsupported
         *   labels (e.g. `none` on older models) surface as an endpoint error the user can fix by
         *   picking Default.
         * - Anthropic: a thinking budget, plus a raised `max_tokens` (the API requires
         *   `max_tokens > budget_tokens`). ENABLING thinking is skipped in the tool loop
         *   ([toolLoop]): Anthropic requires the emitted thinking blocks to be replayed verbatim
         *   with tool results, and the chat loop replays turns as plain text. Disabling is safe.
         * - Google: a thinking budget; 0 disables (supported by Flash models). Tool-call replay
         *   without signatures is covered by the Koog client's fallback thought signature.
         * - Ollama: only an on/off `think` flag exists.
         * - OpenRouter/DeepSeek/Mistral: Koog 1.1.1 exposes no reasoning knob for these clients,
         *   so the level is ignored (endpoint default).
         */
        internal fun reasoningParams(
            provider: AiProvider,
            level: AiReasoningLevel,
            toolLoop: Boolean,
        ): LLMParams? {
            if (level == AiReasoningLevel.DEFAULT) return null
            return when (provider) {
                AiProvider.OPENAI, AiProvider.CUSTOM -> {
                    OpenAIChatParams(reasoningEffort = level.toReasoningEffort())
                }

                AiProvider.ANTHROPIC -> {
                    anthropicReasoningParams(level, toolLoop)
                }

                AiProvider.GOOGLE -> {
                    GoogleParams(
                        thinkingConfig = GoogleThinkingConfig(thinkingBudget = level.toBudget()),
                    )
                }

                AiProvider.OLLAMA -> {
                    OllamaParams(think = level != AiReasoningLevel.NONE)
                }

                AiProvider.OPENROUTER, AiProvider.DEEPSEEK, AiProvider.MISTRAL -> {
                    null
                }
            }
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

        private fun openAiRuntime(
            config: AiConfig,
            httpFactory: KoogHttpClient.Factory,
        ): AiRuntime {
            // TolerantOpenAILLMClient subclasses OpenAILLMClient to skip non-standard trailing SSE
            // chunks (cost/usage frames) that would otherwise abort streaming after real frames but
            // before Koog's terminal End frame. See TolerantOpenAILLMClient for the Koog seam facts.
            val client =
                TolerantOpenAILLMClient(
                    apiKey = config.apiKey.ifBlank { "unused" },
                    settings = OpenAIClientSettings(baseUrl = normalizeBaseUrl(config.effectiveBaseUrl)),
                    httpClientFactory = httpFactory,
                )
            val model =
                LLModel(
                    provider = LLMProvider.OpenAI,
                    id = config.model,
                    capabilities =
                        listOf(
                            // Without an OpenAIEndpoint capability the client cannot pick a request
                            // shape and fails with "Cannot determine proper LLM params". Compatible
                            // third-party servers implement chat/completions, not the Responses API.
                            LLMCapability.OpenAIEndpoint.Completions,
                            LLMCapability.Completion,
                            LLMCapability.Temperature,
                            LLMCapability.Tools,
                            LLMCapability.ToolChoice,
                        ),
                    contextLength = DEFAULT_CONTEXT_LENGTH,
                )
            return AiRuntime(MultiLLMPromptExecutor(LLMProvider.OpenAI to client), model, client)
        }

        private fun anthropicRuntime(
            config: AiConfig,
            httpFactory: KoogHttpClient.Factory,
        ): AiRuntime {
            val model =
                LLModel(
                    provider = LLMProvider.Anthropic,
                    id = config.model,
                    capabilities = standardCapabilities(),
                    contextLength = DEFAULT_CONTEXT_LENGTH,
                )
            // The Anthropic client resolves the wire model name through modelVersionsMap and
            // throws "Unsupported model" for anything absent, so the user's model must be mapped
            // explicitly (the default map only covers Koog's predefined AnthropicModels).
            val settings =
                if (config.baseUrl.isBlank()) {
                    AnthropicClientSettings(modelVersionsMap = mapOf(model to config.model))
                } else {
                    AnthropicClientSettings(
                        modelVersionsMap = mapOf(model to config.model),
                        baseUrl = trimBaseUrl(config.baseUrl),
                    )
                }
            val client = AnthropicLLMClient(config.apiKey, settings, httpFactory)
            return AiRuntime(MultiLLMPromptExecutor(LLMProvider.Anthropic to client), model, client)
        }

        private fun googleRuntime(
            config: AiConfig,
            httpFactory: KoogHttpClient.Factory,
        ): AiRuntime {
            val settings =
                if (config.baseUrl.isBlank()) {
                    GoogleClientSettings()
                } else {
                    GoogleClientSettings(baseUrl = trimBaseUrl(config.baseUrl))
                }
            val client = GoogleLLMClient(config.apiKey, settings, httpFactory)
            val model =
                LLModel(
                    provider = LLMProvider.Google,
                    id = config.model,
                    capabilities = standardCapabilities(),
                    contextLength = DEFAULT_CONTEXT_LENGTH,
                )
            return AiRuntime(MultiLLMPromptExecutor(LLMProvider.Google to client), model, client)
        }

        private fun mistralRuntime(
            config: AiConfig,
            httpFactory: KoogHttpClient.Factory,
        ): AiRuntime {
            val settings =
                if (config.baseUrl.isBlank()) {
                    MistralAIClientSettings()
                } else {
                    MistralAIClientSettings(baseUrl = trimBaseUrl(config.baseUrl))
                }
            val client = MistralAILLMClient(config.apiKey, settings, httpFactory)
            val model =
                LLModel(
                    provider = LLMProvider.MistralAI,
                    id = config.model,
                    capabilities = standardCapabilities(),
                    contextLength = DEFAULT_CONTEXT_LENGTH,
                )
            return AiRuntime(MultiLLMPromptExecutor(LLMProvider.MistralAI to client), model, client)
        }

        private fun deepSeekRuntime(
            config: AiConfig,
            httpFactory: KoogHttpClient.Factory,
        ): AiRuntime {
            val settings =
                if (config.baseUrl.isBlank()) {
                    DeepSeekClientSettings()
                } else {
                    DeepSeekClientSettings(baseUrl = trimBaseUrl(config.baseUrl))
                }
            val client = DeepSeekLLMClient(config.apiKey, settings, httpFactory)
            val model =
                LLModel(
                    provider = LLMProvider.DeepSeek,
                    id = config.model,
                    capabilities = standardCapabilities(),
                    contextLength = DEFAULT_CONTEXT_LENGTH,
                )
            return AiRuntime(MultiLLMPromptExecutor(LLMProvider.DeepSeek to client), model, client)
        }

        private fun openRouterRuntime(
            config: AiConfig,
            httpFactory: KoogHttpClient.Factory,
        ): AiRuntime {
            val settings =
                if (config.baseUrl.isBlank()) {
                    OpenRouterClientSettings()
                } else {
                    OpenRouterClientSettings(baseUrl = trimBaseUrl(config.baseUrl))
                }
            val client = OpenRouterLLMClient(config.apiKey, settings, httpFactory)
            val model =
                LLModel(
                    provider = LLMProvider.OpenRouter,
                    id = config.model,
                    capabilities = standardCapabilities(),
                    contextLength = DEFAULT_CONTEXT_LENGTH,
                )
            return AiRuntime(MultiLLMPromptExecutor(LLMProvider.OpenRouter to client), model, client)
        }

        private fun ollamaRuntime(
            config: AiConfig,
            httpFactory: KoogHttpClient.Factory,
        ): AiRuntime {
            val client =
                OllamaClient(
                    httpClientFactory = httpFactory,
                    baseUrl = trimBaseUrl(config.effectiveBaseUrl),
                )
            val model =
                LLModel(
                    provider = LLMProvider.Ollama,
                    id = config.model,
                    capabilities =
                        listOf(
                            LLMCapability.Completion,
                            LLMCapability.Temperature,
                            LLMCapability.Tools,
                        ),
                    contextLength = DEFAULT_CONTEXT_LENGTH,
                )
            return AiRuntime(MultiLLMPromptExecutor(LLMProvider.Ollama to client), model, client)
        }

        /**
         * Capabilities for the native (non-OpenAI-shaped) clients. [LLMCapability.Thinking] is
         * included so the Anthropic/Google clients accept reasoning content on replayed assistant
         * messages (Google injects its fallback thought signature only for Thinking-capable models).
         */
        private fun standardCapabilities(): List<LLMCapability> =
            listOf(
                LLMCapability.Completion,
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking,
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
}
