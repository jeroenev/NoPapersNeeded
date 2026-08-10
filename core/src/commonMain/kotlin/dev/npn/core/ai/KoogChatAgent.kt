package dev.npn.core.ai

import ai.koog.agents.chatMemory.feature.ChatMemory
import ai.koog.agents.chatMemory.feature.ChatMemoryPreProcessor
import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.agent.functionalStrategy
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.params.LLMParams
import ai.koog.prompt.streaming.StreamFrame
import ai.koog.prompt.streaming.toMessageResponse
import dev.npn.core.db.NpnDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Koog-backed streaming chat agent. The functional strategy keeps the app's plain-text answers. */
class KoogChatAgent(
    private val executor: PromptExecutor,
    private val model: LLModel,
    private val db: NpnDatabase,
    private val params: LLMParams?,
    private val systemPrompt: String,
    private val maxIterations: Int,
) {
    private val historyProvider = RoomChatHistoryProvider(db)
    private val chatTools = LibraryAiTools.chatToolsFor(db)
    private val toolRegistry = ToolRegistry { LibraryAiTools.forDatabase(db).forEach(::tool) }

    fun run(
        question: String,
        conversationId: String,
    ): Flow<AiChatEvent> =
        channelFlow {
            val strategy =
                functionalStrategy<String, String>("npn-chat") {
                    llm.writeSession { appendPrompt { user(question) } }
                    val answer = StringBuilder()
                    var iteration = 0
                    var finished = false
                    while (!finished && iteration < maxIterations) {
                        val calls = mutableListOf<ToolCall>()
                        val responseFrames = mutableListOf<StreamFrame>()
                        llm.writeSession {
                            requestLLMStreaming().collect { frame ->
                                when (frame) {
                                    is StreamFrame.TextDelta -> {
                                        responseFrames += frame
                                        answer.append(frame.text)
                                        send(AiChatEvent.TextDelta(frame.text))
                                    }

                                    is StreamFrame.ToolCallComplete -> {
                                        frame.toUsableToolCall(calls.size)?.let { call ->
                                            responseFrames += frame.withSafeArguments(call)
                                            calls += ToolCall(call.id, call.name, call.argsJson)
                                        }
                                    }

                                    else -> {
                                        responseFrames += frame
                                        Unit
                                    }
                                }
                            }
                            val response = responseFrames.toMessageResponse()
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
                    agentConfig = aiConfig(),
                    strategy = strategy,
                    toolRegistry = toolRegistry,
                ) {
                    install(ChatMemory) {
                        chatHistoryProvider = historyProvider
                        addPreProcessor(SystemPreservingWindow(160))
                    }
                }
            agent.run(question, conversationId)
        }

    suspend fun history(conversationId: String): List<AiChatTurn> =
        KoogChatHistoryMapper.map(historyProvider.load(conversationId)).mapNotNull { item ->
            when (item) {
                is AiChatHistoryItem.UserTurn -> AiChatTurn(AiRole.USER, item.text)
                is AiChatHistoryItem.AssistantTurn -> AiChatTurn(AiRole.ASSISTANT, item.text)
                is AiChatHistoryItem.ToolUse -> null
            }
        }

    private suspend fun dispatch(call: ToolCall): String =
        chatTools.firstOrNull { it.name == call.name }?.let { tool ->
            runCatching { tool.call(call.argsJson) }
                .getOrElse { "Error running ${call.name}: ${it.message ?: "unknown error"}" }
        } ?: "Error: unknown tool \"${call.name}\"."

    private fun aiConfig() =
        ai.koog.agents.core.agent.config.AIAgentConfig(
            prompt = prompt("npn-chat", params ?: LLMParams()) { system(systemPrompt) },
            model = model,
            maxAgentIterations = maxIterations,
        )

    private data class ToolCall(
        val id: String,
        val name: String,
        val argsJson: String,
    )
}

private class SystemPreservingWindow(
    private val size: Int,
) : ChatMemoryPreProcessor {
    override fun preprocess(messages: List<Message>): List<Message> {
        if (messages.size <= size + 1) return messages
        return listOf(messages.first()) + messages.takeLast(size)
    }
}

private val TOOL_ARGS_JSON =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

private fun StreamFrame.ToolCallComplete.withSafeArguments(call: UsableToolCall): StreamFrame.ToolCallComplete {
    val args = content.ifBlank { "{}" }
    return if (runCatching { TOOL_ARGS_JSON.parseToJsonElement(args) as? JsonObject }.getOrNull() != null) {
        copy(id = call.id, name = call.name, content = args)
    } else {
        copy(id = call.id, name = call.name, content = "{}")
    }
}
