package dev.npn.core.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.flow.Flow

/**
 * Emits one scripted stream per streaming call, in order; records every prompt seen. Turns are
 * given as flows so a turn can throw mid-stream rather than only replaying a fixed frame list.
 * `KoogChatAgentTest.scriptedExecutor` builds one from plain frame lists.
 */
internal class ScriptedExecutor(
    private val turns: List<Flow<StreamFrame>>,
) : PromptExecutor() {
    val prompts = mutableListOf<Prompt>()
    private var index = 0

    override fun executeStreaming(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> {
        prompts += prompt
        val flow = turns[index]
        index++
        return flow
    }

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): Message.Assistant = Message.Assistant("", ResponseMetaInfo.Empty)

    override suspend fun executeMultipleChoices(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
    ): List<Message.Assistant> = emptyList()

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel,
    ): ModerationResult = error("not used")

    override fun close() = Unit
}
