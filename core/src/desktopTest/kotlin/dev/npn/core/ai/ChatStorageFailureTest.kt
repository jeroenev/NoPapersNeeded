package dev.npn.core.ai

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import dev.npn.core.db.KeyValueEntity
import dev.npn.core.db.TempIndexDb
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFails

/** Unreadable device-local conversations must survive a read or an attempted new turn. */
class ChatStorageFailureTest {
    private val temp = TempIndexDb("npn-chat-storage-failure-test")
    private val chats = temp.deviceDb.keyValueDao()

    @AfterTest
    fun teardown() = temp.close()

    @Test
    fun anUnreadableSessionListCannotBeReplacedByANewQuestion() =
        runTest {
            val key = "${RoomChatHistoryProvider.KEY_PREFIX}session-metadata"
            val stored = """[{"id":"saved","title":{},"updatedAt":1}]"""
            chats.put(KeyValueEntity(key, stored))
            val facade = AiFacadeImpl(temp.db, chats) { AiConfig() }

            assertFails { facade.ask("new", "question").collect() }

            chats.get(key) shouldBe stored
            assertFails { facade.chatSessions() }
        }

    /**
     * One undecodable conversation behind a placeholder title is protected by its own reads; it
     * must not stop the list, or edits to every other session.
     */
    @Test
    fun anUnreadableConversationBehindAPlaceholderTitleLeavesTheOthersUsable() =
        runTest {
            ChatSessions(chats).save(
                listOf(AiChatSession("broken", "New chat", 2L), AiChatSession("ok", "Something", 1L)),
            )
            val brokenKey = "${RoomChatHistoryProvider.KEY_PREFIX}broken"
            val stored = """[{"type":"future-message","content":"saved conversation"}]"""
            chats.put(KeyValueEntity(brokenKey, stored))
            RoomChatHistoryProvider(chats).store("ok", listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)))
            val facade = AiFacadeImpl(temp.db, chats) { AiConfig() }

            facade.chatSessions().map { it.id } shouldContainExactly listOf("broken", "ok")
            facade.clearChatSession("ok")
            facade.deleteChatSession("ok")

            facade.chatSessions().map { it.id } shouldContainExactly listOf("broken")
            chats.get(brokenKey) shouldBe stored
        }

    @Test
    fun anUnreadableTranscriptCannotBeReplacedByANewAnswer() =
        runTest {
            val key = "${RoomChatHistoryProvider.KEY_PREFIX}saved"
            // A message type from a build this one cannot decode is still user content.
            val stored = """[{"type":"future-message","content":"saved conversation"}]"""
            chats.put(KeyValueEntity(key, stored))
            val history = RoomChatHistoryProvider(chats)
            val executor =
                ScriptedExecutor(
                    listOf(
                        listOf(
                            StreamFrame.TextDelta("answer", index = null),
                            StreamFrame.TextComplete("answer", index = null),
                            StreamFrame.End(null, ResponseMetaInfo.Empty),
                        ).asFlow(),
                    ),
                )
            val agent =
                KoogChatAgent(
                    executor,
                    LLModel(LLMProvider.OpenAI, "test", listOf(LLMCapability.Completion), contextLength = 128_000),
                    history,
                    LibraryAiTools.toolSetFor(temp.db, FieldKeys.seedDefinitions()),
                    null,
                    "system",
                    15,
                )

            assertFails { history.load("saved") }
            assertFails { agent.run("next question", "saved").collect() }

            chats.get(key) shouldBe stored
        }
}
