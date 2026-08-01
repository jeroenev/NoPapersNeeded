package dev.npn.core.ai

import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.PromptBuilder
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toStreamFrames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

/**
 * Explicit agentic chat loop over a Koog [PromptExecutor], replacing the opaque `AIAgent` run so
 * the UI can see token streaming and tool use. Emits [AiChatEvent]s as a cold [Flow]; nothing runs
 * until collected and cancelling the collection stops the request.
 *
 * Each iteration builds a fresh prompt (system prompt + budget-trimmed history + this run's
 * accumulated tool call/result messages), streams one assistant turn, forwards its text, and — if
 * the turn ended in tool calls — runs each tool and loops. A turn with no tool calls ends the run
 * with [AiChatEvent.Done]. Injected with executor/model/tools so it is testable with a fake
 * executor and no database.
 */
class AiChatLoop(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val tools: List<AiChatTool>,
    private val systemPrompt: String,
    private val maxIterations: Int,
) {

    private val toolsByName: Map<String, AiChatTool> = tools.associateBy { it.name }
    private val toolDescriptors = tools.map { it.descriptor }

    fun run(history: List<AiChatTurn>, question: String): Flow<AiChatEvent> = flow {
        val trimmed = AiText.trimHistory(history)
        val scratch = mutableListOf<ScratchMessage>()
        val answer = StringBuilder()
        var iteration = 0

        while (iteration < maxIterations) {
            val prompt = buildPrompt(trimmed, question, scratch)
            val turn = streamTurn(prompt, firstIteration = iteration == 0, answer)

            if (turn.toolCalls.isEmpty()) {
                emit(AiChatEvent.Done(answer.toString()))
                return@flow
            }

            // Record this turn's own narration BEFORE its tool-call messages so subsequent
            // iterations replay the pre-tool text the model emitted (standard OpenAI-compatible
            // shape: an assistant text message followed by assistant tool-call messages).
            scratch += ScratchMessage.AssistantToolCalls(turn.text, turn.toolCalls)
            for (call in turn.toolCalls) {
                emit(AiChatEvent.ToolCallStarted(call.name, call.argsJson))
                val result = dispatch(call)
                emit(AiChatEvent.ToolCallFinished(call.name, call.argsJson, result))
                scratch += ScratchMessage.ToolResult(call.id, call.name, result)
            }
            iteration++
        }

        // Iteration cap: match the old AIAgent maxIterations semantics loosely by ending the run
        // with whatever text accumulated rather than throwing, so a tool-happy model still returns
        // a usable (if partial) answer instead of an error.
        emit(AiChatEvent.Done(answer.toString()))
    }

    /**
     * Stream one assistant turn. Forwards text deltas and collects completed tool calls. On a
     * transport/decode error the recovery depends on what was already captured:
     * - Nothing captured yet on the FIRST iteration -> silently retry that turn non-streaming and
     *   emit the whole answer as one delta (see plan risk note).
     * - Frames were already seen when the stream throws (e.g. a well-formed turn cut short by a
     *   malformed trailing SSE chunk that aborts before Koog's terminal End frame): rescue whatever
     *   was captured rather than discarding a completed turn's work. Tool calls -> return them so
     *   the loop executes them and keeps going (still streaming next iteration); otherwise non-blank
     *   text -> treat as the final answer. If nothing usable was captured, rethrow.
     * [CancellationException] is always rethrown so cancellation is never swallowed.
     */
    private suspend fun FlowCollector<AiChatEvent>.streamTurn(
        prompt: Prompt,
        firstIteration: Boolean,
        answer: StringBuilder,
    ): TurnResult {
        val toolCalls = mutableListOf<ToolCall>()
        val turnText = StringBuilder()
        var sawAnyFrame = false
        try {
            executor.executeStreaming(prompt, model, toolDescriptors).collect { frame ->
                sawAnyFrame = true
                processFrame(frame, answer, turnText, toolCalls)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (sawAnyFrame) return rescuePartialTurn(turnText, toolCalls, e)
            if (!firstIteration) throw e
            return fallbackNonStreaming(prompt, answer)
        }
        return TurnResult(turnText.toString(), toolCalls)
    }

    /**
     * The stream threw after emitting frames. Prefer captured tool calls (the loop runs them and
     * continues), then any non-blank narration as the final answer; if neither was captured there is
     * nothing to salvage, so rethrow the original error.
     */
    private fun rescuePartialTurn(
        turnText: StringBuilder,
        toolCalls: List<ToolCall>,
        error: Throwable,
    ): TurnResult {
        if (toolCalls.isNotEmpty() || turnText.isNotBlank()) {
            return TurnResult(turnText.toString(), toolCalls)
        }
        throw error
    }

    /** Fold one streamed frame into the accumulators, emitting text deltas as they arrive. */
    private suspend fun FlowCollector<AiChatEvent>.processFrame(
        frame: StreamFrame,
        answer: StringBuilder,
        turnText: StringBuilder,
        toolCalls: MutableList<ToolCall>,
    ) {
        when (frame) {
            is StreamFrame.TextDelta -> {
                answer.append(frame.text)
                turnText.append(frame.text)
                emit(AiChatEvent.TextDelta(frame.text))
            }
            is StreamFrame.ToolCallComplete -> toolCalls += frame.toToolCall(toolCalls.size)
            else -> Unit
        }
    }

    /**
     * Non-streaming fallback: one blocking completion with the same tool descriptors, then fed
     * through [toStreamFrames] so any tool calls the endpoint returned are handled by the exact
     * same frame-processing path as the streaming case (text emitted as one delta, tool calls
     * collected so the loop can execute them and continue).
     */
    private suspend fun FlowCollector<AiChatEvent>.fallbackNonStreaming(
        prompt: Prompt,
        answer: StringBuilder,
    ): TurnResult {
        val response = executor.execute(prompt, model, toolDescriptors)
        val toolCalls = mutableListOf<ToolCall>()
        val turnText = StringBuilder()
        for (frame in response.toStreamFrames()) {
            processFrame(frame, answer, turnText, toolCalls)
        }
        return TurnResult(turnText.toString(), toolCalls)
    }

    private suspend fun dispatch(call: ToolCall): String {
        val tool = toolsByName[call.name] ?: return "Error: unknown tool \"${call.name}\"."
        return runCatching { tool.call(call.argsJson) }
            .getOrElse { e -> "Error running ${call.name}: ${e.message ?: "unknown error"}" }
    }

    private fun buildPrompt(
        history: TrimmedHistory,
        question: String,
        scratch: List<ScratchMessage>,
    ): Prompt = prompt("npn-chat") {
        system(systemPrompt)
        if (history.omittedEarlier) system(OMITTED_NOTE)
        for (turn in history.turns) {
            when (turn.role) {
                AiRole.USER -> user(turn.text)
                AiRole.ASSISTANT -> assistant(turn.text)
            }
        }
        user(question)
        for (message in scratch) {
            when (message) {
                is ScratchMessage.AssistantToolCalls -> appendAssistantTurn(message)
                is ScratchMessage.ToolResult -> toolResult(message.name, message.result, message.id)
            }
        }
    }

    /**
     * Replay one executed assistant turn as a SINGLE `Message.Assistant` carrying the turn's
     * narration text (if any) plus ALL of its tool calls, using the `assistant { }` parts builder.
     *
     * This grouping is load-bearing, not cosmetic. Koog's `PromptBuilder.assistant(text)` and each
     * `PromptBuilder.toolCall(...)` append a SEPARATE `Message.Assistant`, and the OpenAI client
     * serializes those one-to-one (no merging). So the old `assistant(text)` + per-call `toolCall()`
     * shape produced consecutive assistant messages — narration, then one assistant message per tool
     * call. Strict OpenAI-compatible gateways (verified live against DeepSeek via opencode.ai/zen/go)
     * reject that: a turn that emitted multiple tool calls must be replayed as ONE assistant message
     * whose `tool_calls` array holds every call, or the request 400s ("invalid request error"). A
     * single tool call happens to be accepted either way, but multiple calls in a turn are not, so
     * we always group. Putting the calls in one `assistant { }` block collects them into one
     * `Message.Assistant.parts` list (Text first, then every `MessagePart.Tool.Call`), which the
     * client renders as a single `{role:"assistant", content, tool_calls:[...]}` message.
     */
    private fun PromptBuilder.appendAssistantTurn(turn: ScratchMessage.AssistantToolCalls) {
        assistant {
            if (turn.text.isNotBlank()) +turn.text
            // toolCall(id, tool, args): the id links each call to its result for OpenAI-compatible
            // servers and must be the exact id the endpoint issued, so keep it stable.
            for (call in turn.calls) toolCall(call.id, call.name, call.argsJson)
        }
    }

    // The streamed id is preferred; when absent, fall back to a per-turn-unique id (the same
    // tool called twice in one turn must not collapse to one id, which id ?: name would do).
    private fun StreamFrame.ToolCallComplete.toToolCall(index: Int): ToolCall =
        ToolCall(id = id ?: "call_$index", name = name, argsJson = content)

    private data class ToolCall(val id: String, val name: String, val argsJson: String)

    private data class TurnResult(val text: String, val toolCalls: List<ToolCall>)

    private sealed interface ScratchMessage {
        /** The narration [text] the model emitted this turn (may be blank) plus its tool [calls]. */
        data class AssistantToolCalls(val text: String, val calls: List<ToolCall>) : ScratchMessage
        data class ToolResult(val id: String, val name: String, val result: String) : ScratchMessage
    }

    private companion object {
        const val OMITTED_NOTE = "(Note: earlier conversation was omitted to fit the context.)"
    }
}
