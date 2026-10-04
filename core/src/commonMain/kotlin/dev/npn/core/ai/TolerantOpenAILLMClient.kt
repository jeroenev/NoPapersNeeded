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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/**
 * [OpenAILLMClient] that tolerates non-standard trailing SSE chunks emitted by some
 * OpenAI-compatible gateways (e.g. `opencode.ai/zen/go`), which append cost/usage frames like
 * `{"choices":[],"x-opencode-type":"inference-cost","cost":"..."}` before and after
 * `data: [DONE]`. Those chunks are missing the `id`/`created`/`model`/`object` fields that Koog's
 * strict `OpenAIChatCompletionStreamResponse` serializer requires, so the stock
 * [OpenAILLMClient.decodeStreamingResponse] throws `MissingFieldException`. Because that decode
 * happens per-chunk inside the SSE loop (see `AbstractOpenAILLMClient.executeStreaming`), the
 * exception aborts the whole stream AFTER the real text/tool-call frames were emitted but BEFORE
 * Koog flushes its terminal `End` frame — silently discarding the turn's tool calls.
 *
 * Seam: in Koog 1.1.1, `OpenAILLMClient` is a non-final `public` class and
 * `decodeStreamingResponse(data: String): OpenAIChatCompletionStreamResponse` is `protected`
 * (declared abstract on `AbstractOpenAILLMClient`, overridden concretely here). This override is
 * the cleanest seam (no reflection, no flow-wrapping hacks). Koog's `getJson()` is module-internal
 * so unusable here; a plain lenient [Json] is enough for the pre-decode `choices` inspection (we
 * only read one array field). Everything used here is Koog multiplatform API, so this compiles for
 * both the desktop and android targets.
 *
 * Strategy: a chunk carrying no usable `choices` cannot produce any [ai.koog.prompt.streaming.StreamFrame]
 * (Koog's `processStreamingResponse` uses `choices.firstOrNull()` and skips the chunk when it is
 * empty), so instead of decoding it strictly we short-circuit and return a benign, well-formed
 * empty-choices response. As a belt-and-braces fallback we also swallow a strict-decode failure the
 * same way, so any other malformed-but-choiceless chunk cannot abort the stream either.
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
     * The [decodeStreamingResponse] seam above does not reach it: Koog 1.1.1 decodes Responses
     * events in a `private` `executeResponsesStreaming`, against a `protected final` `json` and a
     * closed `OpenAIStreamEvent` polymorphic scope with no unknown-event fallback (unchanged in
     * 1.2.0), so there is nothing to override per-chunk. What *is* overridable is the whole flow.
     *
     * Once Koog has emitted its terminal [StreamFrame.End] the turn is complete — every text delta
     * and tool call has already been handed to the caller (verified against a captured gateway
     * stream in `ZenGoResponsesStreamTest`), so a failure to parse whatever the gateway says next
     * describes nothing the caller still needs. Before End, a failure is real and still propagates:
     * a truncated turn must not be mistaken for a finished one.
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
     * this `internal` entry point). A choiceless chunk cannot yield a frame, so it is short-circuited
     * to a benign empty response; otherwise strict decoding runs, and a strict failure on a chunk
     * that turns out to be choiceless anyway is swallowed the same way rather than aborting the
     * stream.
     */
    internal fun decodeStreamingResponseTolerant(data: String): OpenAIChatCompletionStreamResponse {
        if (hasNoChoices(data)) return EMPTY_STREAM_RESPONSE
        return try {
            super.decodeStreamingResponse(data)
        } catch (_: Exception) {
            if (hasNoChoices(data)) EMPTY_STREAM_RESPONSE else throw ChunkDecodeException(data)
        }
    }

    private fun hasNoChoices(data: String): Boolean =
        runCatching {
            val choices = NpnJson.Lenient.parseToJsonElement(data).jsonObject["choices"]
            choices == null || (choices as? JsonArray)?.isEmpty() == true
        }.getOrDefault(false)

    private companion object {
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

/** Signals a streaming chunk that could not be decoded and did carry (malformed) choices. */
internal class ChunkDecodeException(
    chunk: String,
) : IllegalStateException("Failed to decode streaming chunk: ${chunk.take(200)}")
