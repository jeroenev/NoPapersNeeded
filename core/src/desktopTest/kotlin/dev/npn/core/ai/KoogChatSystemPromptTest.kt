package dev.npn.core.ai

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import dev.npn.core.db.TempIndexDb
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test

/** What system prompt a chat turn is asked under, whatever the conversation began with. */
class KoogChatSystemPromptTest {
    private val temp = TempIndexDb("npn-koog-system-prompt")
    private val db = temp.db

    @AfterTest
    fun teardown() = temp.close()

    private val model =
        LLModel(
            provider = LLMProvider.OpenAI,
            id = "test",
            capabilities = listOf(LLMCapability.Completion, LLMCapability.Tools),
            contextLength = 128_000,
        )

    private fun textFrames(text: String): List<StreamFrame> =
        listOf(
            StreamFrame.TextDelta(text, index = null),
            StreamFrame.TextComplete(text, index = null),
            StreamFrame.End(null, ResponseMetaInfo.Empty),
        )

    /**
     * The system prompt carries the field registry, the language and today's date, all of which
     * can change between two questions; a later turn must be asked under the current one, not the
     * one the conversation began with.
     */
    @Test
    fun eachTurnIsAskedUnderTheCurrentSystemPrompt() =
        runTest {
            val earlier =
                listOf(
                    Message.System("system v1", RequestMetaInfo.Empty),
                    Message.User("question 1", RequestMetaInfo.Empty),
                    Message.Assistant("answer 1", ResponseMetaInfo.Empty),
                )
            RoomChatHistoryProvider(db.keyValueDao()).store("session-1", earlier)
            val executor = ScriptedExecutor(listOf(textFrames("answer 2").asFlow()))

            KoogChatAgent(
                executor,
                model,
                RoomChatHistoryProvider(db.keyValueDao()),
                LibraryAiTools.toolSetFor(db, FieldKeys.seedDefinitions()),
                null,
                "system v2",
                15,
            ).run("question 2", "session-1").collect()

            executor.prompts
                .single()
                .messages
                .filterIsInstance<Message.System>()
                .map { it.textContent() } shouldContainExactly listOf("system v2")
            RoomChatHistoryProvider(db.keyValueDao())
                .load("session-1")
                .filterIsInstance<Message.System>()
                .map { it.textContent() } shouldContainExactly listOf("system v2")
        }
}
