package dev.npn.core.ai

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
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
 * @param factory what [client] was built by, which also knows how to ask it for its models.
 * @param ownedTransport the base Ktor client this runtime created for itself and must release in
 *  [close]; null when the caller supplied one through [build] and keeps ownership of it.
 */
internal class AiRuntime(
    val executor: PromptExecutor,
    val model: LLModel,
    private val client: LLMClient,
    private val factory: ProviderClientFactory,
    private val ownedTransport: HttpClient? = null,
) : AutoCloseable {
    /** Ids of the models the configured endpoint offers, for the settings model picker. */
    suspend fun listModelIds(): List<String> = factory.listModels(client).sorted()

    /**
     * Release the HTTP resources this runtime owns. Call once the runtime is no longer cached.
     *
     * Koog derives its per-provider client from the base transport with `HttpClient.config {}`,
     * and a derived Ktor client does not own the engine — so closing [client] alone would leak
     * the engine of a transport we created. A caller-supplied transport is the caller's to close.
     */
    override fun close() {
        client.close()
        ownedTransport?.close()
    }

    companion object {
        private const val DEFAULT_CONTEXT_LENGTH = 128_000L

        private const val OPENCODE_SESSION_HEADER = "x-opencode-session"

        /**
         * Build the runtime for [config]. Every endpoint client the app makes comes from here: the
         * facade's cached one ([AiRuntimeCache]) and the short-lived ones of [AiSelfTest] and
         * [AiModelCatalog].
         *
         * [transport] is the base Ktor client every request to the endpoint goes through, and the
         * test seam: Koog layers its own plugins on a derived copy, so plugins installed on it (a
         * test's recorder or redirect) see every call the runtime makes, whichever provider it is
         * for. Provider-specific wire requirements (the OpenCode Go session header) are added on
         * top here ([ProviderClientFactory.transport]), never in place of what the caller installed.
         *
         * A null [transport] means "a plain client, owned by the runtime": [newTransport] creates it
         * here and [close] releases it — or this does, when a provider factory throws (a malformed
         * base URL does), since no runtime then exists to release it. A supplied one stays the
         * caller's to close.
         */
        fun build(
            config: AiConfig,
            transport: HttpClient? = null,
            newTransport: () -> HttpClient = { HttpClient() },
        ): AiRuntime {
            val owned = if (transport == null) newTransport() else null
            val base = transport ?: checkNotNull(owned)
            val factory = AiProviderClients.of(config.provider)
            val model = LLModel(factory.provider, config.model, factory.capabilities(config), DEFAULT_CONTEXT_LENGTH)
            val client =
                try {
                    val http = KtorKoogHttpClient.Factory(factory.transport(base))
                    factory.client(config.apiKey, config.effectiveBaseUrl, model, http)
                } catch (failure: Throwable) {
                    owned?.close()
                    throw failure
                }
            return AiRuntime(
                MultiLLMPromptExecutor(factory.provider to client),
                model,
                client,
                factory,
                ownedTransport = owned,
            )
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
         * per-client [fallbackSessionId]: they send a batch-constant system prompt (see
         * `AiPrompts.editSystem`), so one id per batch helps the gateway's prefix caching.
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
         * Params for the one-shot AI-edit/suggestion prompts, from [AiConfig.reasoningEdit].
         * These run without tools, so every provider mapping is safe to apply.
         */
        fun suggestionParams(config: AiConfig): LLMParams =
            reasoningParams(config.provider, config.model, config.reasoningEdit, toolLoop = false) ?: LLMParams()

        /**
         * Params for one page transcription ([AiFacadeImpl.ocrEngine]). Not [suggestionParams]:
         * reasoning buys nothing on a verbatim transcription, would be paid per page, and on
         * Anthropic takes most of `max_tokens`, truncating a dense page into a write-once sidecar.
         *
         * Only Anthropic gets an override (thinking off, a `max_tokens` above Koog's 2048 default).
         * The others keep their endpoint defaults, because an explicit "no reasoning" label errors
         * on some models (see [reasoningParams]), and a page that cannot be read at all is worse
         * than an unwanted thought.
         */
        fun ocrParams(config: AiConfig): LLMParams = AiProviderClients.of(config.provider).ocrParams()

        /**
         * Params for the chat assistant, from [AiConfig.reasoningAssistant]; null keeps the
         * prompt's defaults. Chat runs a tool loop, which restricts what can be applied (see
         * [reasoningParams]).
         */
        fun chatParams(config: AiConfig): LLMParams? =
            reasoningParams(config.provider, config.model, config.reasoningAssistant, toolLoop = true)

        /**
         * Map one user-facing [AiReasoningLevel] onto provider-specific Koog params; null means
         * "no override" (either [AiReasoningLevel.DEFAULT] or an unsupported combination). Each
         * provider's mapping, and why, is its [ProviderClientFactory.reasoning] in [AiProviderClients].
         */
        internal fun reasoningParams(
            provider: AiProvider,
            model: String,
            level: AiReasoningLevel,
            toolLoop: Boolean,
        ): LLMParams? =
            if (level == AiReasoningLevel.DEFAULT) {
                null
            } else {
                AiProviderClients.of(provider).reasoning(model, level, toolLoop)
            }
    }
}
