package dev.npn.core.ai

import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel

/**
 * Koog executor + model pair built from one [AiConfig]. Rebuilt only when the config
 * changes (the facade caches it), so the underlying HTTP client is reused across calls.
 */
internal class AiRuntime(
    val executor: PromptExecutor,
    val model: LLModel,
    private val client: OpenAILLMClient,
) {
    /** Release the underlying HTTP client. Call once the runtime is no longer cached. */
    fun close() {
        client.close()
    }

    companion object {
        private const val DEFAULT_CONTEXT_LENGTH = 128_000L

        fun build(config: AiConfig): AiRuntime {
            // TolerantOpenAILLMClient subclasses OpenAILLMClient to skip non-standard trailing SSE
            // chunks (cost/usage frames) that would otherwise abort streaming after real frames but
            // before Koog's terminal End frame. See TolerantOpenAILLMClient for the Koog seam facts.
            val client = TolerantOpenAILLMClient(
                apiKey = config.apiKey.ifBlank { "unused" },
                settings = OpenAIClientSettings(baseUrl = normalizeBaseUrl(config.baseUrl)),
                httpClientFactory = KtorKoogHttpClient.Factory(),
            )
            val model = LLModel(
                provider = LLMProvider.OpenAI,
                id = config.model,
                capabilities = listOf(
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

        /**
         * Reduce a pasted endpoint to the server root. Koog appends `v1/chat/completions`
         * itself, so the full endpoint URL (as printed by most provider docs) and a bare
         * `/v1` suffix both have to be stripped or the path doubles up.
         */
        internal fun normalizeBaseUrl(raw: String): String =
            raw.trim()
                .trimEnd('/')
                .removeSuffix("/chat/completions")
                .trimEnd('/')
                .removeSuffix("/v1")
    }
}
