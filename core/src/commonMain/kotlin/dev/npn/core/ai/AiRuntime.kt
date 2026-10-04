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
import ai.koog.prompt.executor.clients.openai.OpenAIResponsesParams
import ai.koog.prompt.executor.clients.openai.base.models.ReasoningEffort
import ai.koog.prompt.executor.clients.openai.models.ReasoningConfig
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
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestPipeline
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Koog executor + model pair built from one [AiConfig]. Rebuilt only when the config
 * changes (the facade caches it), so the underlying HTTP client is reused across calls.
 *
 * @param ownedTransport the base Ktor client this runtime created for itself and must release in
 *  [close]; null when the caller supplied one through [build] and keeps ownership of it.
 */
internal class AiRuntime(
    val executor: PromptExecutor,
    val model: LLModel,
    private val client: LLMClient,
    private val ownedTransport: HttpClient? = null,
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

    /**
     * Release the HTTP resources this runtime owns. Call once the runtime is no longer cached.
     *
     * Koog derives its per-provider client from the base transport with `HttpClient.config {}`,
     * and a derived Ktor client does not own the engine — so closing [client] alone would leak
     * the engine of a transport we created. A caller-supplied transport is the caller's to close.
     */
    fun close() {
        client.close()
        ownedTransport?.close()
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

        /**
         * Output budget for one page transcription ([AiOcr]). Comfortably more than any single
         * scanned page holds, and the ceiling every Claude 3+ model accepts — raising it further
         * would be an API error on the older ones, and costs nothing to leave here since output
         * tokens are billed as produced, not as reserved.
         */
        private const val OCR_ANSWER_MAX_TOKENS = 4_096

        private const val OPENCODE_SESSION_HEADER = "x-opencode-session"

        /**
         * Build the runtime for [config] over [transport], the base Ktor client every request to
         * the endpoint goes through. Koog layers its own plugins on a derived copy, so plugins
         * installed on [transport] — a metering interceptor, request logging, a test's recorder —
         * see every call the runtime makes, whichever provider it is for. Provider-specific wire
         * requirements (the OpenCode Go session header) are added on top here, never in place of
         * what the caller installed.
         *
         * A null [transport] means "a plain client, owned by the runtime": it is created here and
         * released by [close]. A supplied one stays the caller's to close, because it outlives any
         * one runtime by design (the facade rebuilds runtimes when the config changes).
         */
        fun build(
            config: AiConfig,
            transport: HttpClient? = null,
        ): AiRuntime {
            val owned = if (transport == null) HttpClient() else null
            val base = transport ?: checkNotNull(owned)
            val httpFactory =
                KtorKoogHttpClient.Factory(
                    if (config.provider == AiProvider.OPENCODE_GO) base.withOpenCodeSession() else base,
                )
            val built =
                when (config.provider) {
                    AiProvider.OPENAI, AiProvider.CUSTOM, AiProvider.OPENCODE_GO -> openAiRuntime(config, httpFactory)
                    AiProvider.ANTHROPIC -> anthropicRuntime(config, httpFactory)
                    AiProvider.GOOGLE -> googleRuntime(config, httpFactory)
                    AiProvider.MISTRAL -> mistralRuntime(config, httpFactory)
                    AiProvider.DEEPSEEK -> deepSeekRuntime(config, httpFactory)
                    AiProvider.OPENROUTER -> openRouterRuntime(config, httpFactory)
                    AiProvider.OLLAMA -> ollamaRuntime(config, httpFactory)
                }
            return AiRuntime(built.executor, built.model, built.client, ownedTransport = owned)
        }

        /**
         * The OpenCode Go gateway rejects requests that carry no `x-opencode-session` header (one
         * stable id per conversation), so a client bound for it derives a copy that stamps one.
         *
         * Koog's provider clients expose no per-request header hook, so the header is stamped
         * here, in the request pipeline: each request reports the [AiGatewaySession] its caller
         * put in the coroutine context — chat passes its chat session id, so one conversation
         * keeps one id across turns.
         *
         * Requests without one (one-shot edit suggestions, the settings model list) share the
         * per-client [fallbackSessionId]. That sharing is deliberate rather than a shortcut:
         * those runs are not conversations, and they send a batch-constant system prompt (see
         * `AiPrompts.editSystem`), so keeping a whole batch on one id helps the
         * gateway's prefix caching instead of fragmenting it.
         *
         * Derived with `HttpClient.config {}`, so it keeps every plugin the receiver already has
         * and shares its engine; the receiver stays the one that owns that engine.
         */
        @OptIn(ExperimentalUuidApi::class)
        internal fun HttpClient.withOpenCodeSession(): HttpClient {
            val fallbackSessionId = Uuid.random().toString()
            return config {
                install("npn-opencode-session") {
                    requestPipeline.intercept(HttpRequestPipeline.Before) {
                        context.headers[OPENCODE_SESSION_HEADER] =
                            currentGatewaySessionId() ?: fallbackSessionId
                    }
                }
            }
        }

        /**
         * Whether this model must be called through OpenAI's Responses API rather than
         * `chat/completions`.
         *
         * The OpenCode Go gateway does not serve one API for everything: it routes the OpenAI-family
         * models to the native OpenAI SDK (Responses API) and everything else to an
         * OpenAI-compatible chat/completions backend. Calling the wrong one is not a graceful
         * degradation — the gateway answers HTTP 500 (verified live: `gpt-5.6-luna` 500s on
         * chat/completions and answers on `/v1/responses`; `kimi-k2.6`, `glm-5.1`, `hy3` do the
         * exact opposite). The same split is what models.dev encodes for the `opencode-go` provider,
         * where only the `gpt-*` entries carry `provider.npm = "@ai-sdk/openai"`.
         *
         * Deliberately scoped to [AiProvider.OPENCODE_GO]. [AiProvider.OPENAI] serves both APIs for
         * these ids, so there is no bug to fix there and switching would be an unverified change to
         * a working path; [AiProvider.CUSTOM] points at self-hosted servers (llama.cpp, LiteLLM)
         * that implement chat/completions and often *name* their model `gpt-...`, so the same
         * inference would break them.
         *
         * A model id is free text, so this is a heuristic, not a lookup. It errs towards
         * chat/completions: an unrecognised id keeps today's behaviour.
         */
        internal fun usesResponsesApi(
            provider: AiProvider,
            model: String,
        ): Boolean {
            if (provider != AiProvider.OPENCODE_GO) return false
            // Gateways sometimes qualify ids with the upstream vendor ("openai/gpt-5.1").
            val id = model.trim().lowercase().substringAfterLast('/')
            return OPENAI_FAMILY_MODEL_ID.containsMatchIn(id)
        }

        /** `gpt-4o`, `gpt-5.6-luna`, `o3-mini`, `gpt-5.1-codex-max`, ... */
        private val OPENAI_FAMILY_MODEL_ID = Regex("^(gpt-|o[134](-|$))|codex")

        /**
         * Params for the one-shot AI-edit/suggestion prompts, from [AiConfig.reasoningEdit].
         * These run without tools, so every provider mapping is safe to apply.
         */
        fun suggestionParams(config: AiConfig): LLMParams =
            reasoningParams(config.provider, config.model, config.reasoningEdit, toolLoop = false) ?: LLMParams()

        /**
         * Params for one page transcription ([AiOcr]).
         *
         * Deliberately not [suggestionParams]. That maps [AiConfig.reasoningEdit], and neither
         * half of it suits OCR: reasoning buys nothing on a verbatim transcription, and it would
         * be charged once per PAGE here rather than once per document. On Anthropic it is worse
         * than pointless — [anthropicReasoningParams] hands most of `max_tokens` to the thinking
         * budget, so a dense page can be truncated mid-transcription into a write-once sidecar.
         *
         * Only Anthropic gets an override, because only Anthropic needs one: Koog's client falls
         * back to a flat 2048 `max_tokens`, which a dense page can exceed. The other providers
         * keep their endpoint defaults rather than an explicit "no reasoning" label, because
         * those labels are themselves a failure mode (see [reasoningParams]: `reasoning_effort:
         * none` errors on older OpenAI-shaped models, and a zero Google thinking budget errors on
         * the Pro models). An unwanted thought on a transcription call is a smaller problem than
         * a page that cannot be read at all.
         */
        fun ocrParams(config: AiConfig): LLMParams =
            when (config.provider) {
                AiProvider.ANTHROPIC -> {
                    AnthropicParams(
                        maxTokens = OCR_ANSWER_MAX_TOKENS,
                        thinking = AnthropicThinking.Disabled(),
                    )
                }

                else -> {
                    LLMParams()
                }
            }

        /**
         * Params for the chat assistant, from [AiConfig.reasoningAssistant]; null keeps the
         * prompt's defaults. Chat runs a tool loop, which restricts what can be applied (see
         * [reasoningParams]).
         */
        fun chatParams(config: AiConfig): LLMParams? =
            reasoningParams(config.provider, config.model, config.reasoningAssistant, toolLoop = true)

        /**
         * Map one user-facing [AiReasoningLevel] onto provider-specific Koog params; null means
         * "no override" (either [AiReasoningLevel.DEFAULT] or an unsupported combination).
         *
         * Provider notes:
         * - OpenAI/custom/OpenCode Go: `reasoning_effort` label. Ignored by non-reasoning models;
         *   unsupported labels (e.g. `none` on older models) surface as an endpoint error the user
         *   can fix by picking Default. The params TYPE has to agree with the endpoint the model is
         *   called on ([usesResponsesApi]): Koog's `determineParams` treats explicit
         *   `OpenAIChatParams` as a demand for chat/completions and fails the call when the model
         *   does not declare that endpoint, so a Responses-API model needs `OpenAIResponsesParams`.
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
            model: String,
            level: AiReasoningLevel,
            toolLoop: Boolean,
        ): LLMParams? {
            if (level == AiReasoningLevel.DEFAULT) return null
            return when (provider) {
                AiProvider.OPENAI, AiProvider.CUSTOM, AiProvider.OPENCODE_GO -> {
                    if (usesResponsesApi(provider, model)) {
                        OpenAIResponsesParams(reasoning = ReasoningConfig(effort = level.toReasoningEffort()))
                    } else {
                        OpenAIChatParams(reasoningEffort = level.toReasoningEffort())
                    }
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
                            // shape and fails with "Cannot determine proper LLM params". Exactly one
                            // is declared, never both: given a choice Koog's `determineParams`
                            // always prefers chat/completions, which is the wrong half of the
                            // OpenCode Go split (see usesResponsesApi). Compatible third-party
                            // servers implement chat/completions, not the Responses API, so that
                            // stays the default.
                            if (usesResponsesApi(config.provider, config.model)) {
                                LLMCapability.OpenAIEndpoint.Responses
                            } else {
                                LLMCapability.OpenAIEndpoint.Completions
                            },
                            LLMCapability.Completion,
                            LLMCapability.Temperature,
                            LLMCapability.Tools,
                            LLMCapability.ToolChoice,
                            LLMCapability.Vision.Image,
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
                AnthropicClientSettings(
                    modelVersionsMap = mapOf(model to config.model),
                    baseUrl = trimBaseUrl(config.effectiveBaseUrl),
                )
            val client = AnthropicLLMClient(config.apiKey, settings, httpFactory)
            return AiRuntime(MultiLLMPromptExecutor(LLMProvider.Anthropic to client), model, client)
        }

        private fun googleRuntime(
            config: AiConfig,
            httpFactory: KoogHttpClient.Factory,
        ): AiRuntime {
            val settings = GoogleClientSettings(baseUrl = trimBaseUrl(config.effectiveBaseUrl))
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
            val settings = MistralAIClientSettings(baseUrl = trimBaseUrl(config.effectiveBaseUrl))
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
            val settings = DeepSeekClientSettings(baseUrl = trimBaseUrl(config.effectiveBaseUrl))
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
            val settings = OpenRouterClientSettings(baseUrl = trimBaseUrl(config.effectiveBaseUrl))
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
                            LLMCapability.Vision.Image,
                        ),
                    contextLength = DEFAULT_CONTEXT_LENGTH,
                )
            return AiRuntime(MultiLLMPromptExecutor(LLMProvider.Ollama to client), model, client)
        }

        /**
         * Capabilities for the native (non-OpenAI-shaped) clients. [LLMCapability.Thinking] is
         * included so the Anthropic/Google clients accept reasoning content on replayed assistant
         * messages (Google injects its fallback thought signature only for Thinking-capable models).
         *
         * [LLMCapability.Vision.Image] is included because page-image OCR ([AiOcr]) sends image
         * parts, and the clients reject an image for a model that does not declare it. Like Tools
         * and Thinking above, this is a claim rather than a fact: the model id is free text the
         * user typed, so nothing here can know what it really supports. A model without vision
         * fails the OCR call at the endpoint with a message the user can act on, which is strictly
         * better than the client refusing to send it and every model needing a capability probe.
         */
        private fun standardCapabilities(): List<LLMCapability> =
            listOf(
                LLMCapability.Completion,
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
                LLMCapability.Thinking,
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
}
