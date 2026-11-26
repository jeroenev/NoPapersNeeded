package dev.npn.core.ai

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import dev.npn.core.util.NpnJson
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * One model call's usage within a chat turn: the provider's report, plus the size of what was sent
 * and what came back for an estimate when the provider reports none.
 */
internal data class ChatUsageInfo(
    /** The provider's report; null when the call stopped before its end frame carried one. */
    val metaInfo: ResponseMetaInfo?,
    /** Characters sent: the messages in the replay window and the tool schemas. */
    val promptChars: Int,
    val answerChars: Int,
)

/** Koog-backed streaming chat agent. The functional strategy keeps the app's plain-text answers. */
internal class KoogChatAgent(
    private val executor: PromptExecutor,
    private val model: LLModel,
    /** Where the conversation is loaded from and stored back to, keyed by conversation id. */
    private val historyProvider: ChatHistoryProvider,
    private val toolSet: LibraryToolSet,
    private val params: LLMParams?,
    private val systemPrompt: String,
    private val maxIterations: Int,
) {
    private val toolRegistry = ToolRegistry { toolSet.tools.forEach(::tool) }

    /** What the tool schemas add to every call; see [schemaChars]. */
    private val toolSchemaChars = toolSet.tools.sumOf { schemaChars(it.descriptor) }

    @Suppress("LongMethod") // The turn loop's mutable state (answer, frames, calls) is shared by every branch.
    fun run(
        question: String,
        conversationId: String,
        onUsage: suspend (ChatUsageInfo) -> Unit = {},
    ): Flow<AiChatEvent> =
        channelFlow {
            val basePrompt = prompt("npn-chat", params ?: LLMParams()) { system(systemPrompt) }
            val strategy =
                functionalStrategy<String, String>("npn-chat") {
                    // Fixed for the whole tool loop: see ChatHistoryWindow.forRequest.
                    val replayedTurns =
                        llm.writeSession {
                            // Chat memory has replaced the prompt with the stored conversation, whose system
                            // message is the one its first turn was asked under. The field registry, the
                            // language and today's date may have changed since, so this turn's replaces it.
                            prompt =
                                prompt.withMessages { stored ->
                                    basePrompt.messages + stored.filterNot { it is Message.System }
                                }
                            appendPrompt { user(question) }
                            ChatHistoryWindow.turnsForRequest(prompt.messages)
                        }
                    val answer = StringBuilder()
                    var iteration = 0
                    var finished = false
                    while (!finished && iteration < maxIterations) {
                        val calls = mutableListOf<UsableToolCall>()
                        val responseFrames = mutableListOf<StreamFrame>()
                        val turnText = StringBuilder()
                        var textFlushed = false
                        llm.writeSession {
                            streamMeasured(replayedTurns, onUsage) { frame ->
                                when (frame) {
                                    is StreamFrame.TextDelta -> {
                                        responseFrames += frame
                                        turnText.append(frame.text)
                                        answer.append(frame.text)
                                        send(AiChatEvent.TextDelta(frame.text))
                                    }

                                    is StreamFrame.ToolCallComplete -> {
                                        frame.toUsableToolCall()?.let { call ->
                                            responseFrames += frame.withSafeArguments(call)
                                            calls += call
                                        }
                                    }

                                    else -> {
                                        if (frame is StreamFrame.TextComplete) textFlushed = true
                                        responseFrames += frame
                                    }
                                }
                            }
                            val response =
                                responseFrames.withFlushedText(turnText.toString(), textFlushed).toMessageResponse()
                            appendPrompt { message(response) }
                            if (calls.isEmpty()) {
                                finished = true
                            } else {
                                calls.forEach { call ->
                                    val args = toolArgs(call.argsJson)
                                    send(AiChatEvent.ToolCallStarted(call.name, args))
                                    val result = dispatch(call)
                                    send(AiChatEvent.ToolCallFinished(call.name, args, result))
                                    appendPrompt { toolResult(call.name, result, call.id) }
                                }
                            }
                        }
                        iteration++
                    }
                    send(AiChatEvent.Done(answer.toString()))
                    answer.toString()
                }
            val agent =
                AIAgent(
                    promptExecutor = executor,
                    agentConfig = aiConfig(basePrompt),
                    strategy = strategy,
                    toolRegistry = toolRegistry,
                ) {
                    install(ChatMemory) {
                        chatHistoryProvider = historyProvider
                        addPreProcessor(ChatHistoryWindow.memory)
                    }
                }
            agent.run(question, conversationId)
        }

    /**
     * Stream one model call over the replay window (see [streamOverReplayWindow]), handing each
     * frame to [onFrame], and report the call's usage: at its end frame, or, for a call that
     * streamed something and stopped before one (the user stopped the turn, the connection
     * dropped), with no report and an estimate, since it was paid for all the same. A call refused
     * before any frame (a 429) cost nothing and is not reported.
     */
    private suspend fun AIAgentLLMWriteSession.streamMeasured(
        turns: Int,
        onUsage: suspend (ChatUsageInfo) -> Unit,
        onFrame: suspend (StreamFrame) -> Unit,
    ) {
        var sentChars = -1
        var answerChars = 0
        var ended = false
        try {
            streamOverReplayWindow(turns) { frame ->
                // Read while the replay window is the prompt: that is what was sent.
                if (sentChars < 0) sentChars = prompt.messages.sumOf { it.textContent().length } + toolSchemaChars
                if (frame is StreamFrame.TextDelta) answerChars += frame.text.length
                if (frame is StreamFrame.End) {
                    ended = true
                    onUsage(ChatUsageInfo(frame.metaInfo, sentChars, answerChars))
                }
                onFrame(frame)
            }
        } finally {
            if (sentChars >= 0 && !ended) {
                withContext(NonCancellable) { onUsage(ChatUsageInfo(null, sentChars, answerChars)) }
            }
        }
    }

    // A failing tool is reported to the model as its result; a cancelled one ends the turn.
    private suspend fun dispatch(call: UsableToolCall): String =
        toolSet.calls[call.name]?.let { tool ->
            runCatchingCancellable { tool(call.argsJson) }
                .getOrElse { "Error running ${call.name}: ${it.reason()}" }
        } ?: "Error: unknown tool \"${call.name}\"."

    private fun aiConfig(prompt: Prompt) =
        ai.koog.agents.core.agent.config.AIAgentConfig(
            prompt = prompt,
            model = model,
            maxAgentIterations = maxIterations,
        )
}

/**
 * Characters one tool's schema adds to a call, for the estimate when the provider reports no usage:
 * its name and description and each parameter's name, description and type, plus the JSON a
 * function-calling request wraps them in (`{"type":"function","function":{"name":…,
 * "parameters":{"type":"object","properties":{…},"required":[…]}}}`).
 */
private fun schemaChars(tool: ToolDescriptor): Int =
    TOOL_JSON_CHARS + tool.name.length + tool.description.length +
        (tool.requiredParameters + tool.optionalParameters).sumOf { parameter ->
            PARAMETER_JSON_CHARS + parameter.name.length + parameter.description.length + parameter.type.name.length
        }

/** The JSON around one function's name, description and parameter object. */
private const val TOOL_JSON_CHARS = 110

/** The JSON around one parameter's name, type and description, and its place in `required`. */
private const val PARAMETER_JSON_CHARS = 40

/**
 * Stream the model's next response to the newest [turns] turns [ChatHistoryWindow] replays,
 * handing each frame to [onFrame]. The session's prompt keeps the whole conversation, for the
 * transcript and the next turn.
 */
private suspend fun AIAgentLLMWriteSession.streamOverReplayWindow(
    turns: Int,
    onFrame: suspend (StreamFrame) -> Unit,
) {
    val conversation = prompt
    prompt = conversation.withMessages { ChatHistoryWindow.forRequest(it, turns) }
    try {
        requestLLMStreaming().collect { onFrame(it) }
    } finally {
        prompt = conversation
    }
}

/**
 * `toMessageResponse()` materialises only `*Complete` frames, so a turn's text survives into the
 * next prompt (and into the persisted history) only if the client flushed a
 * [StreamFrame.TextComplete]. Koog's chat/completions path does that before every tool call and at
 * `End`; its Responses path emits deltas and `End` only. When no flush arrived, stand in for it
 * with the text this turn actually streamed — ahead of the tool-call frames, so the replayed
 * assistant message keeps OpenAI's content-then-tool_calls order. When one did arrive the client is
 * already doing the job and the frames are left alone, so text is never counted twice.
 */
private fun List<StreamFrame>.withFlushedText(
    turnText: String,
    textFlushed: Boolean,
): List<StreamFrame> =
    if (textFlushed || turnText.isEmpty()) {
        this
    } else {
        listOf(StreamFrame.TextComplete(turnText, index = null)) + this
    }

private fun StreamFrame.ToolCallComplete.withSafeArguments(call: UsableToolCall): StreamFrame.ToolCallComplete {
    val args = content.ifBlank { "{}" }
    return if (runCatching { NpnJson.Lenient.parseToJsonElement(args) as? JsonObject }.getOrNull() != null) {
        copy(id = call.id, name = call.name, content = args)
    } else {
        copy(id = call.id, name = call.name, content = "{}")
    }
}

/** A tool call that is safe to dispatch and replay into the next prompt. */
internal data class UsableToolCall(
    val id: String,
    val name: String,
    val argsJson: String,
)

/**
 * Drop malformed gateway frames instead of dispatching an empty tool name. A call without an id
 * gets a random one: the conversation is stored and replayed, so a fallback id must not repeat
 * anywhere in it, across iterations and turns alike.
 */
internal fun StreamFrame.ToolCallComplete.toUsableToolCall(): UsableToolCall? {
    val toolName = name.trim().takeIf { it.isNotEmpty() } ?: return null
    return UsableToolCall(
        id = id?.takeUnless { it.isBlank() } ?: fallbackCallId(),
        name = toolName,
        argsJson = content,
    )
}

@OptIn(ExperimentalUuidApi::class)
private fun fallbackCallId(): String = "call_" + Uuid.random().toHexString()
