package dev.npn.core.ai

import ai.koog.agents.chatMemory.feature.ChatHistoryProvider
import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.agent.session.AIAgentLLMWriteSession
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import dev.npn.core.util.NpnJson
import dev.npn.core.util.reason
import dev.npn.core.util.runCatchingCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.JsonObject

/** Koog-backed streaming chat agent. The functional strategy keeps the app's plain-text answers. */
class KoogChatAgent(
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

    fun run(
        question: String,
        conversationId: String,
    ): Flow<AiChatEvent> =
        channelFlow {
            val basePrompt = prompt("npn-chat", params ?: LLMParams()) { system(systemPrompt) }
            val strategy =
                functionalStrategy<String, String>("npn-chat") {
                    llm.writeSession {
                        // Chat memory has replaced the prompt with the stored conversation, whose system
                        // message is the one its first turn was asked under. The field registry, the
                        // language and today's date may have changed since, so this turn's replaces it.
                        prompt =
                            prompt.withMessages { stored ->
                                basePrompt.messages + stored.filterNot { it is Message.System }
                            }
                        appendPrompt { user(question) }
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
                            streamOverReplayWindow { frame ->
                                when (frame) {
                                    is StreamFrame.TextDelta -> {
                                        responseFrames += frame
                                        turnText.append(frame.text)
                                        answer.append(frame.text)
                                        send(AiChatEvent.TextDelta(frame.text))
                                    }

                                    is StreamFrame.ToolCallComplete -> {
                                        frame.toUsableToolCall(calls.size)?.let { call ->
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
                                    send(AiChatEvent.ToolCallStarted(call.name, call.argsJson))
                                    val result = dispatch(call)
                                    send(AiChatEvent.ToolCallFinished(call.name, call.argsJson, result))
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
 * Stream the model's next response to the part of the conversation [ChatHistoryWindow] replays,
 * handing each frame to [onFrame]. The session's prompt keeps the whole conversation, for the
 * transcript and the next turn.
 */
private suspend fun AIAgentLLMWriteSession.streamOverReplayWindow(onFrame: suspend (StreamFrame) -> Unit) {
    val conversation = prompt
    prompt = conversation.withMessages(ChatHistoryWindow::forRequest)
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
