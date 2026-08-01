package dev.npn.core.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.streaming.StreamFrame
import kotlinx.coroutines.CancellationException
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AiChatLoopTest {

    private val model = LLModel(
        provider = LLMProvider.OpenAI,
        id = "test",
        capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
        contextLength = 128_000,
    )

    private fun loop(
        executor: PromptExecutor,
        tools: List<AiChatTool> = emptyList(),
        maxIterations: Int = 15,
    ) = AiChatLoop(executor, model, tools, "system", maxIterations)

    /** [ScriptedExecutor] over plain frame lists (each turn replays its frames then completes). */
    private fun scriptedExecutor(turns: List<List<StreamFrame>>) =
        ScriptedExecutor(turns.map { it.asFlow() })

    private fun end(): StreamFrame = StreamFrame.End(null, ResponseMetaInfo.Empty)

    private fun textFrames(vararg parts: String): List<StreamFrame> =
        parts.map { StreamFrame.TextDelta(it, index = null) } + end()

    private fun toolFrame(name: String, args: String): StreamFrame =
        StreamFrame.ToolCallComplete(id = "call-1", name = name, content = args, index = null)

    /** A flow that emits [frames] in order then throws, simulating a stream aborted before End. */
    private fun throwAfter(vararg frames: StreamFrame): Flow<StreamFrame> = flow {
        frames.forEach { emit(it) }
        error("malformed trailing chunk")
    }

    private fun fakeTool(name: String, result: String) = object : AiChatTool {
        override val name = name
        override val descriptor = ToolDescriptor(name, "desc", emptyList(), emptyList())
        var calls = 0
        override suspend fun call(argsJson: String): String {
            calls++
            return result
        }
    }

    @Test
    fun plainAnswerStreamsTextThenDone() = runTest {
        val executor = scriptedExecutor(listOf(textFrames("Hel", "lo")))
        val events = loop(executor).run(emptyList(), "hi").collectList()

        events[0] shouldBe AiChatEvent.TextDelta("Hel")
        events[1] shouldBe AiChatEvent.TextDelta("lo")
        val done = events.last().shouldBeInstanceOf<AiChatEvent.Done>()
        done.fullText shouldBe "Hello"
    }

    @Test
    fun answerAfterToolCall() = runTest {
        val tool = fakeTool("search_documents", "one hit")
        val executor = scriptedExecutor(
            listOf(
                listOf(toolFrame("search_documents", "{\"query\":\"tax\"}"), end()),
                textFrames("Found it"),
            ),
        )
        val events = loop(executor, listOf(tool)).run(emptyList(), "find tax").collectList()

        events[0] shouldBe AiChatEvent.ToolCallStarted("search_documents", "{\"query\":\"tax\"}")
        events[1] shouldBe AiChatEvent.ToolCallFinished("search_documents", "{\"query\":\"tax\"}", "one hit")
        events[2] shouldBe AiChatEvent.TextDelta("Found it")
        events.last().shouldBeInstanceOf<AiChatEvent.Done>().fullText shouldBe "Found it"
    }

    @Test
    fun multiToolIteration() = runTest {
        val search = fakeTool("search_documents", "hits")
        val tags = fakeTool("list_tags", "tag list")
        val executor = scriptedExecutor(
            listOf(
                listOf(
                    toolFrame("search_documents", "{\"query\":\"x\"}"),
                    toolFrame("list_tags", "{}"),
                    end(),
                ),
                textFrames("done"),
            ),
        )
        val events = loop(executor, listOf(search, tags)).run(emptyList(), "q").collectList()

        val started = events.filterIsInstance<AiChatEvent.ToolCallStarted>().map { it.name }
        started shouldContainExactly listOf("search_documents", "list_tags")
        events.last().shouldBeInstanceOf<AiChatEvent.Done>().fullText shouldBe "done"
    }

    @Test
    fun iterationCapEndsWithDone() = runTest {
        val tool = fakeTool("list_tags", "tags")
        // Every turn calls a tool again, so the loop only stops at the cap.
        val toolTurn = listOf(toolFrame("list_tags", "{}"), end())
        val executor = scriptedExecutor(List(10) { toolTurn })
        val events = loop(executor, listOf(tool), maxIterations = 3).run(emptyList(), "q").collectList()

        events.filterIsInstance<AiChatEvent.ToolCallStarted>() shouldContainExactly
            listOf(
                AiChatEvent.ToolCallStarted("list_tags", "{}"),
                AiChatEvent.ToolCallStarted("list_tags", "{}"),
                AiChatEvent.ToolCallStarted("list_tags", "{}"),
            )
        events.last().shouldBeInstanceOf<AiChatEvent.Done>()
    }

    @Test
    fun streamingFailureFallsBackToNonStreaming() = runTest {
        val executor = FallbackExecutor(Message.Assistant("fallback answer", ResponseMetaInfo.Empty))
        val events = loop(executor).run(emptyList(), "hi").collectList()

        events shouldContainExactly listOf(
            AiChatEvent.TextDelta("fallback answer"),
            AiChatEvent.Done("fallback answer"),
        )
    }

    @Test
    fun fallbackResponseWithToolCallExecutesToolAndContinues() = runTest {
        val tool = fakeTool("search_documents", "one hit")
        // First iteration's streaming throws; execute() returns a tool call. The loop must run the
        // tool and continue to a second (streaming) iteration that produces the final answer.
        val fallback = Message.Assistant(
            listOf(
                MessagePart.Tool.Call(id = "call-1", tool = "search_documents", args = "{\"query\":\"tax\"}"),
            ),
            ResponseMetaInfo.Empty,
        )
        val executor = FallbackExecutor(fallback, streamingTurns = listOf(textFrames("Found it")))
        val events = loop(executor, listOf(tool)).run(emptyList(), "find tax").collectList()

        events[0] shouldBe AiChatEvent.ToolCallStarted("search_documents", "{\"query\":\"tax\"}")
        events[1] shouldBe AiChatEvent.ToolCallFinished("search_documents", "{\"query\":\"tax\"}", "one hit")
        events[2] shouldBe AiChatEvent.TextDelta("Found it")
        events.last().shouldBeInstanceOf<AiChatEvent.Done>().fullText shouldBe "Found it"
        tool.calls shouldBe 1
    }

    @Test
    fun streamThrowAfterToolFrameRescuesToolCallAndContinues() = runTest {
        val tool = fakeTool("search_documents", "one hit")
        // First turn emits a tool-call frame then THROWS before End (the malformed-trailing-chunk
        // failure mode): the loop must still execute the captured tool and continue to a normal
        // second (streaming) iteration that finishes with text — streaming is NOT disabled.
        val executor = ScriptedExecutor(
            listOf(
                throwAfter(toolFrame("search_documents", "{\"query\":\"tax\"}")),
                textFrames("Found it").asFlow(),
            ),
        )
        val events = loop(executor, listOf(tool)).run(emptyList(), "find tax").collectList()

        events[0] shouldBe AiChatEvent.ToolCallStarted("search_documents", "{\"query\":\"tax\"}")
        events[1] shouldBe AiChatEvent.ToolCallFinished("search_documents", "{\"query\":\"tax\"}", "one hit")
        events[2] shouldBe AiChatEvent.TextDelta("Found it")
        events.last().shouldBeInstanceOf<AiChatEvent.Done>().fullText shouldBe "Found it"
        tool.calls shouldBe 1
    }

    @Test
    fun streamThrowAfterTextOnlyEndsWithPartialAnswer() = runTest {
        // Text deltas then a throw with no tool call: the captured narration becomes the final
        // answer (Done) instead of the run dying with an exception.
        val executor = ScriptedExecutor(
            listOf(
                throwAfter(
                    StreamFrame.TextDelta("Hel", index = null),
                    StreamFrame.TextDelta("lo", index = null),
                ),
            ),
        )
        val events = loop(executor).run(emptyList(), "hi").collectList()

        events[0] shouldBe AiChatEvent.TextDelta("Hel")
        events[1] shouldBe AiChatEvent.TextDelta("lo")
        events.last().shouldBeInstanceOf<AiChatEvent.Done>().fullText shouldBe "Hello"
    }

    @Test
    fun cancellationMidStreamIsNotRescued() = runTest {
        val executor = ScriptedExecutor(
            listOf(
                flow {
                    emit(StreamFrame.TextDelta("partial", index = null))
                    throw CancellationException("cancelled")
                },
            ),
        )
        var thrown: Throwable? = null
        try {
            loop(executor).run(emptyList(), "hi").collectList()
        } catch (e: CancellationException) {
            thrown = e
        }
        thrown.shouldBeInstanceOf<CancellationException>()
    }

    @Test
    fun preToolNarrationIsReplayedInNextIterationPrompt() = runTest {
        val tool = fakeTool("search_documents", "one hit")
        val executor = scriptedExecutor(
            listOf(
                listOf(
                    StreamFrame.TextDelta("Let me search.", index = null),
                    toolFrame("search_documents", "{\"query\":\"tax\"}"),
                    end(),
                ),
                textFrames("Found it."),
            ),
        )
        loop(executor, listOf(tool)).run(emptyList(), "find tax").collectList()

        // The second iteration's prompt must contain the pre-tool narration as an assistant
        // message so the model sees what it already said.
        val secondPrompt = executor.prompts[1]
        val assistantTexts = secondPrompt.messages
            .filterIsInstance<Message.Assistant>()
            .map { it.textContent() }
            .filter { it.isNotBlank() }
        assistantTexts shouldContainExactly listOf("Let me search.")
    }

    @Test
    fun duplicateToolCallsGetUniqueFallbackIds() = runTest {
        val tool = fakeTool("list_tags", "tags")
        val executor = scriptedExecutor(
            listOf(
                listOf(
                    // Same tool twice in one turn, both with a null id -> must not collide.
                    StreamFrame.ToolCallComplete(id = null, name = "list_tags", content = "{}", index = null),
                    StreamFrame.ToolCallComplete(id = null, name = "list_tags", content = "{}", index = null),
                    end(),
                ),
                textFrames("done"),
            ),
        )
        loop(executor, listOf(tool)).run(emptyList(), "q").collectList()

        val secondPrompt = executor.prompts[1]
        val toolCallIds = secondPrompt.messages
            .filterIsInstance<Message.Assistant>()
            .flatMap { it.parts }
            .filterIsInstance<MessagePart.Tool.Call>()
            .map { it.id }
        toolCallIds shouldHaveSize 2
        toolCallIds.toSet() shouldHaveSize 2
    }

    @Test
    fun oneTurnsToolCallsReplayAsASingleGroupedAssistantMessage() = runTest {
        // A turn that narrates then emits TWO tool calls must be replayed as ONE Message.Assistant
        // whose parts are [Text, Call, Call] — NOT three assistant messages (narration + one per
        // call). Strict OpenAI-compatible gateways (verified live: DeepSeek via opencode.ai/zen/go)
        // 400 on consecutive assistant tool-call messages; the calls must be one tool_calls array
        // and the narration must share that message (content-then-tool_calls).
        val search = fakeTool("search_documents", "hits")
        val tags = fakeTool("list_tags", "tag list")
        val query = "{\"query\":\"x\"}"
        val executor = scriptedExecutor(
            listOf(
                listOf(
                    StreamFrame.TextDelta("Let me look.", index = null),
                    StreamFrame.ToolCallComplete("call_a", "search_documents", content = query, index = null),
                    StreamFrame.ToolCallComplete("call_b", "list_tags", content = "{}", index = null),
                    end(),
                ),
                textFrames("done"),
            ),
        )
        loop(executor, listOf(search, tags)).run(emptyList(), "q").collectList()

        val replay = executor.prompts[1].messages
        // Exactly one assistant message across the whole replay prompt: no consecutive assistants.
        val assistants = replay.filterIsInstance<Message.Assistant>()
        assistants shouldHaveSize 1
        // That single assistant message carries the narration text and BOTH calls, in order.
        val parts = assistants.single().parts
        parts.filterIsInstance<MessagePart.Text>().map { it.text } shouldContainExactly listOf("Let me look.")
        parts.filterIsInstance<MessagePart.Tool.Call>().map { it.tool to it.id } shouldContainExactly
            listOf("search_documents" to "call_a", "list_tags" to "call_b")
        // The text part precedes the call parts within the message (OpenAI content-then-tool_calls).
        val textIdx = parts.indexOfFirst { it is MessagePart.Text }
        (parts.indexOfFirst { it is MessagePart.Tool.Call } > textIdx) shouldBe true
        // Each tool result references its call id, and results are separate (tool-role) messages.
        val resultIds = replay.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>().map { it.id }
        resultIds shouldContainExactly listOf("call_a", "call_b")
    }

    private suspend fun Flow<AiChatEvent>.collectList(): List<AiChatEvent> {
        val out = mutableListOf<AiChatEvent>()
        collect { out += it }
        return out
    }

    /**
     * Emits one scripted stream per streaming call, in order; records every prompt seen. Turns are
     * given as flows so a turn can throw mid-stream (aborted before End) rather than only replaying
     * a fixed frame list. Use [scriptedExecutor] for the common frame-list case.
     */
    private class ScriptedExecutor(private val turns: List<Flow<StreamFrame>>) : PromptExecutor() {
        val prompts = mutableListOf<Prompt>()
        private var index = 0
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            prompts += prompt
            val flow = turns[index]
            index++
            return flow
        }

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            Message.Assistant("", ResponseMetaInfo.Empty)

        override suspend fun executeMultipleChoices(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Assistant> = emptyList()

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("not used")

        override fun close() = Unit
    }

    /**
     * First streaming call throws before any frame; execute() returns [nonStreamingResponse].
     * Subsequent streaming calls replay [streamingTurns] in order, so a fallback response that
     * carries a tool call can drive a second (streaming) iteration.
     */
    private class FallbackExecutor(
        private val nonStreamingResponse: Message.Assistant,
        private val streamingTurns: List<List<StreamFrame>> = emptyList(),
    ) : PromptExecutor() {
        private var streamCalls = 0
        override fun executeStreaming(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Flow<StreamFrame> {
            // The FIRST streaming attempt fails to trigger the fallback; later iterations (after a
            // fallback tool call ran) stream normally from the scripted turns.
            val call = streamCalls
            streamCalls++
            if (call == 0) return flow { error("transport error") }
            return streamingTurns[call - 1].asFlow()
        }

        override suspend fun execute(prompt: Prompt, model: LLModel, tools: List<ToolDescriptor>): Message.Assistant =
            nonStreamingResponse

        override suspend fun executeMultipleChoices(
            prompt: Prompt,
            model: LLModel,
            tools: List<ToolDescriptor>,
        ): List<Message.Assistant> = emptyList()

        override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
            error("not used")

        override fun close() = Unit
    }
}
