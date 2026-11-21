package dev.npn.core.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.http.client.KoogHttpClient
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.models.OpenAIChatCompletionStreamResponse
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import dev.npn.core.util.NpnJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * [OpenAILLMClient] that tolerates the non-standard SSE chunks some OpenAI-compatible gateways
 * (e.g. `opencode.ai/zen/go`) send around `data: [DONE]`, such as
 * `{"choices":[],"x-opencode-type":"inference-cost","cost":"..."}`. They lack the fields Koog's
 * strict `OpenAIChatCompletionStreamResponse` requires, and the stock
 * [OpenAILLMClient.decodeStreamingResponse] throws on them inside the SSE loop: after the turn's
 * text and tool calls were emitted but before Koog's terminal `End` frame, so the tool calls are
 * silently lost.
 *
 * A chunk with no `choices` cannot produce a [ai.koog.prompt.streaming.StreamFrame] (Koog skips
 * it), so it decodes to a well-formed empty-choices response instead. Any other chunk still goes
 * through the strict decode, and one that fails it still ends the stream. The override uses only
 * Koog's multiplatform API (`decodeStreamingResponse` is `protected` in Koog 1.3.0); Koog's own
 * `Json` is module-internal, so a lenient one reads the `choices` field.
 */
internal class TolerantOpenAILLMClient(
    apiKey: String,
    settings: OpenAIClientSettings,
    httpClientFactory: KoogHttpClient.Factory,
) : OpenAILLMClient(apiKey, settings, httpClientFactory) {
    override fun decodeStreamingResponse(data: String): OpenAIChatCompletionStreamResponse =
        decodeStreamingResponseTolerant(data)

    /**
     * The same tolerance for the Responses API, where the gateway appends its trailer as an SSE
     * `event: ping` carrying a cost (`{"type":"ping","cost":"0"}`) AFTER `response.completed`.
     *
     * Koog 1.3.0 decodes Responses events privately, with no unknown-event fallback, so only the
     * whole flow can be overridden. Once Koog has emitted its terminal [StreamFrame.End], every
     * text delta and tool call has reached the caller (`ZenGoResponsesStreamTest` checks this
     * against a captured gateway stream), so a parse failure after it is dropped. Before End a
     * failure still propagates: a truncated turn must not pass for a finished one.
     */
    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = super.executeStreaming(prompt, model, tools).endingAtTerminalFrame()

    /**
     * Swallow an upstream failure that happens after [StreamFrame.End]. State lives inside the
     * [flow] builder so it is per-collection rather than per-client, and cancellation is always
     * re-thrown so a cancelled turn is never reported as a completed one.
     */
    private fun Flow<StreamFrame>.endingAtTerminalFrame(): Flow<StreamFrame> =
        flow {
            var ended = false
            this@endingAtTerminalFrame
                .onEach { if (it is StreamFrame.End) ended = true }
                .catch { cause ->
                    if (cause is CancellationException || !ended) throw cause
                }.collect { emit(it) }
        }

    /**
     * Testable core of [decodeStreamingResponse] (the override is `protected`, so tests exercise
     * this `internal` entry point). An in-band error chunk (`{"error":{"message":...}}`, which
     * gateways such as LiteLLM and vLLM send when they fail mid-stream) throws with the server's
     * text, so the turn fails rather than ending as if it were complete. Any other choiceless chunk
     * cannot yield a frame, so it is short-circuited to a benign empty response; every other chunk
     * is decoded strictly, and a failure names the chunk.
     */
    internal fun decodeStreamingResponseTolerant(data: String): OpenAIChatCompletionStreamResponse {
        val chunk = runCatching { NpnJson.Lenient.parseToJsonElement(data) as? JsonObject }.getOrNull()
        chunk?.get("error")?.takeUnless { it is JsonNull }?.let { throw IllegalStateException(errorText(it, data)) }
        if (chunk != null && hasNoChoices(chunk)) return EMPTY_STREAM_RESPONSE
        return try {
            super.decodeStreamingResponse(data)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to decode streaming chunk: ${data.take(CHUNK_PREVIEW)}", e)
        }
    }

    private fun hasNoChoices(chunk: JsonObject): Boolean {
        val choices = chunk["choices"]
        return choices == null || (choices as? JsonArray)?.isEmpty() == true
    }

    /** The server's own words for an in-band [error]: its `message`, else the error itself. */
    private fun errorText(
        error: JsonElement,
        data: String,
    ): String {
        val message = ((error as? JsonObject)?.get("message") as? JsonPrimitive)?.contentOrNull
        val bare = (error as? JsonPrimitive)?.contentOrNull
        return message?.takeIf { it.isNotBlank() } ?: bare?.takeIf { it.isNotBlank() } ?: data.take(CHUNK_PREVIEW)
    }

    private companion object {
        /** How much of an undecodable chunk its failure quotes. */
        const val CHUNK_PREVIEW = 200

        val EMPTY_STREAM_RESPONSE =
            OpenAIChatCompletionStreamResponse(
                choices = emptyList(),
                created = 0L,
                id = "",
                model = "",
                serviceTier = null,
                systemFingerprint = null,
                objectType = "chat.completion.chunk",
                usage = null,
            )
    }
}
