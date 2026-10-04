package dev.npn.core.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Behaviour of the shipped chat loop: the Koog agent drives streaming turns, dispatches the
 * library tools against a real (empty) index, and replays each turn into the next prompt.
 * The executor is scripted, so the assertions are about the loop and the prompt shape it
 * assembles — not about any endpoint.
 */
class KoogChatAgentTest {
    private lateinit var dbDir: File
    private lateinit var db: NpnDatabase

    @BeforeTest
    fun setup() {
        dbDir = Files.createTempDirectory("npn-koog-chat-test").toFile()
        db = npnDatabase(File(dbDir, "index.db"))
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbDir.deleteRecursively()
    }

    private val model =
        LLModel(
            provider = LLMProvider.OpenAI,
            id = "test",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
            contextLength = 128_000,
        )

    private fun agent(
        executor: PromptExecutor,
        maxIterations: Int = 15,
    ) = KoogChatAgent(
        executor,
        model,
        db,
        LibraryAiTools.toolSetFor(db, FieldKeys.seedDefinitions()),
        null,
        "system",
        maxIterations,
    )

    /** [ScriptedExecutor] over plain frame lists (each turn replays its frames then completes). */
    private fun scriptedExecutor(turns: List<List<StreamFrame>>) = ScriptedExecutor(turns.map { it.asFlow() })

    private fun end(): StreamFrame = StreamFrame.End(null, ResponseMetaInfo.Empty)

    /**
     * Assistant text as a Koog LLM client emits it: the deltas the UI streams, then the
     * `TextComplete` the client flushes for the accumulated text (`StreamFrameFlowBuilder` does
     * this before every tool call and at `End`). Only the `*Complete` frames survive
     * `toMessageResponse()`, so the flush is what carries the turn into the next prompt.
     */
    private fun narration(vararg parts: String): List<StreamFrame> =
        parts.map { StreamFrame.TextDelta(it, index = null) } +
            StreamFrame.TextComplete(parts.joinToString(""), index = null)

    private fun textFrames(vararg parts: String): List<StreamFrame> = narration(*parts) + end()

    private fun toolFrame(
        name: String,
        args: String,
    ): StreamFrame = StreamFrame.ToolCallComplete(id = "call-1", name = name, content = args, index = null)

    @Test
    fun plainAnswerStreamsTextThenDone() =
        runTest {
            val executor = scriptedExecutor(listOf(textFrames("Hel", "lo")))
            val events = agent(executor).run("hi", "session-1").collectList()

            events[0] shouldBe AiChatEvent.TextDelta("Hel")
            events[1] shouldBe AiChatEvent.TextDelta("lo")
            events.last().shouldBeInstanceOf<AiChatEvent.Done>().fullText shouldBe "Hello"
        }

    @Test
    fun answerAfterToolCall() =
        runTest {
            val executor =
                scriptedExecutor(
                    listOf(
                        listOf(toolFrame("search_documents", "{\"query\":\"tax\"}"), end()),
                        textFrames("Found it"),
                    ),
                )
            val events = agent(executor).run("find tax", "session-1").collectList()

            events[0] shouldBe AiChatEvent.ToolCallStarted("search_documents", "{\"query\":\"tax\"}")
            events[1] shouldBe
                AiChatEvent.ToolCallFinished("search_documents", "{\"query\":\"tax\"}", NO_TAX_MATCH)
            events[2] shouldBe AiChatEvent.TextDelta("Found it")
            events.last().shouldBeInstanceOf<AiChatEvent.Done>().fullText shouldBe "Found it"
        }

    @Test
    fun multiToolIteration() =
        runTest {
            val executor =
                scriptedExecutor(
                    listOf(
                        listOf(
                            toolFrame("search_documents", "{\"query\":\"x\"}"),
                            toolFrame("list_tags", "{}"),
                            end(),
                        ),
                        textFrames("done"),
                    ),
                )
            val events = agent(executor).run("q", "session-1").collectList()

            val started = events.filterIsInstance<AiChatEvent.ToolCallStarted>().map { it.name }
            started shouldContainExactly listOf("search_documents", "list_tags")
            events.last().shouldBeInstanceOf<AiChatEvent.Done>().fullText shouldBe "done"
        }

    @Test
    fun iterationCapEndsWithDone() =
        runTest {
            // Every turn calls a tool again, so the loop only stops at the cap.
            val toolTurn = listOf(toolFrame("list_tags", "{}"), end())
            val executor = scriptedExecutor(List(10) { toolTurn })
            val events = agent(executor, maxIterations = 3).run("q", "session-1").collectList()

            events.filterIsInstance<AiChatEvent.ToolCallStarted>() shouldContainExactly
                listOf(
                    AiChatEvent.ToolCallStarted("list_tags", "{}"),
                    AiChatEvent.ToolCallStarted("list_tags", "{}"),
                    AiChatEvent.ToolCallStarted("list_tags", "{}"),
                )
            events.last().shouldBeInstanceOf<AiChatEvent.Done>()
        }

    @Test
    fun preToolNarrationIsReplayedInNextIterationPrompt() =
        runTest {
            val executor =
                scriptedExecutor(
                    listOf(
                        narration("Let me search.") +
                            listOf(toolFrame("search_documents", "{\"query\":\"tax\"}"), end()),
                        textFrames("Found it."),
                    ),
                )
            agent(executor).run("find tax", "session-1").collectList()

            // The second iteration's prompt must contain the pre-tool narration as an assistant
            // message so the model sees what it already said.
            val secondPrompt = executor.prompts[1]
            val assistantTexts =
                secondPrompt.messages
                    .filterIsInstance<Message.Assistant>()
                    .map { it.textContent() }
                    .filter { it.isNotBlank() }
            assistantTexts shouldContainExactly listOf("Let me search.")
        }

    @Test
    fun duplicateToolCallsGetUniqueFallbackIds() =
        runTest {
            val executor =
                scriptedExecutor(
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
            agent(executor).run("q", "session-1").collectList()

            val secondPrompt = executor.prompts[1]
            val toolCallIds =
                secondPrompt.messages
                    .filterIsInstance<Message.Assistant>()
                    .flatMap { it.parts }
                    .filterIsInstance<MessagePart.Tool.Call>()
                    .map { it.id }
            toolCallIds shouldHaveSize 2
            toolCallIds.toSet() shouldHaveSize 2
        }

    @Test
    fun oneTurnsToolCallsReplayAsASingleGroupedAssistantMessage() =
        runTest {
            // A turn that narrates then emits TWO tool calls must be replayed as ONE Message.Assistant
            // whose parts are [Text, Call, Call] — NOT three assistant messages (narration + one per
            // call). Strict OpenAI-compatible gateways (verified live: DeepSeek via opencode.ai/zen/go)
            // 400 on consecutive assistant tool-call messages; the calls must be one tool_calls array
            // and the narration must share that message (content-then-tool_calls).
            val query = "{\"query\":\"x\"}"
            val executor =
                scriptedExecutor(
                    listOf(
                        narration("Let me look.") +
                            listOf(
                                StreamFrame.ToolCallComplete("call_a", "search_documents", query, index = null),
                                StreamFrame.ToolCallComplete("call_b", "list_tags", "{}", index = null),
                                end(),
                            ),
                        textFrames("done"),
                    ),
                )
            agent(executor).run("q", "session-1").collectList()

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

    /**
     * Koog's Responses-API path emits text deltas and the terminal `End` but never flushes a
     * `TextComplete` (unlike the chat/completions path), and `toMessageResponse()` only
     * materialises `*Complete` frames. The loop must not depend on that flush: a delta-only turn
     * is still the assistant's answer, so it has to reach the persisted history — otherwise
     * reopening the chat shows the questions and tool rows with no replies.
     */
    @Test
    fun aDeltaOnlyTurnIsStillPersistedAsTheAssistantAnswer() =
        runTest {
            val executor =
                scriptedExecutor(
                    listOf(listOf(StreamFrame.TextDelta("Hi ", null), StreamFrame.TextDelta("there", null), end())),
                )
            agent(executor).run("hi", "session-1").collectList()

            RoomChatHistoryProvider(db)
                .load("session-1")
                .filterIsInstance<Message.Assistant>()
                .map { it.textContent() } shouldContainExactly listOf("Hi there")
        }

    @Test
    fun aDeltaOnlyNarrationIsReplayedInTheNextIterationPrompt() =
        runTest {
            val executor =
                scriptedExecutor(
                    listOf(
                        listOf(
                            StreamFrame.TextDelta("Let me search.", null),
                            toolFrame("search_documents", "{\"query\":\"tax\"}"),
                            end(),
                        ),
                        listOf(StreamFrame.TextDelta("Found it.", null), end()),
                    ),
                )
            agent(executor).run("find tax", "session-1").collectList()

            executor.prompts[1]
                .messages
                .filterIsInstance<Message.Assistant>()
                .map { it.textContent() }
                .filter { it.isNotBlank() } shouldContainExactly listOf("Let me search.")
        }

    /**
     * A long session must not re-send every old tool output with each question: the request
     * replays only the latest turns' results in full. The stored history keeps all of them, since
     * the reopened transcript shows them.
     */
    @Test
    fun oldToolResultsAreStubbedInTheRequestButKeptInTheHistory() =
        runTest {
            val output = "r".repeat(2_000)
            val earlier =
                listOf(Message.System("system", RequestMetaInfo.Empty)) +
                    (1..3).flatMap { n ->
                        listOf(
                            Message.User("question $n", RequestMetaInfo.Empty),
                            Message.Assistant(
                                listOf(MessagePart.Tool.Call("call-$n", "search_documents", "{}")),
                                ResponseMetaInfo.Empty,
                            ),
                            Message.User(
                                MessagePart.Tool.Result("call-$n", "search_documents", output),
                                RequestMetaInfo.Empty,
                            ),
                            Message.Assistant("answer $n", ResponseMetaInfo.Empty),
                        )
                    }
            RoomChatHistoryProvider(db).store("session-1", earlier)
            val executor = scriptedExecutor(listOf(textFrames("answer 4")))

            agent(executor).run("question 4", "session-1").collectList()

            // Sent: the previous turn's result in full, the older two as stubs.
            executor.prompts
                .single()
                .messages
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool.Result>()
                .map { it.output == output } shouldContainExactly listOf(false, false, true)
            // Kept: every result as it was.
            RoomChatHistoryProvider(db)
                .load("session-1")
                .flatMap { it.parts }
                .filterIsInstance<MessagePart.Tool.Result>()
                .map { it.output } shouldContainExactly List(3) { output }
        }

    private suspend fun Flow<AiChatEvent>.collectList(): List<AiChatEvent> {
        val out = mutableListOf<AiChatEvent>()
        collect { out += it }
        return out
    }

    /**
     * Emits one scripted stream per streaming call, in order; records every prompt seen. Turns are
     * given as flows so a turn can throw mid-stream rather than only replaying a fixed frame list.
     * Use [scriptedExecutor] for the common frame-list case.
     */
    private class ScriptedExecutor(
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

    private companion object {
        /** What the real `search_documents` tool answers for "tax" against an empty index. */
        const val NO_TAX_MATCH = "No documents match \"tax\". Try fewer or different terms."
    }
}
