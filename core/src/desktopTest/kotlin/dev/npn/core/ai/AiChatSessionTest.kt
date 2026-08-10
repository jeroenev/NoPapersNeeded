package dev.npn.core.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

class AiChatSessionTest {
    private lateinit var dbDir: File
    private lateinit var db: NpnDatabase

    @BeforeTest
    fun setup() {
        dbDir = Files.createTempDirectory("npn-chat-session-test").toFile()
        db = npnDatabase(File(dbDir, "index.db"))
    }

    @AfterTest
    fun teardown() {
        db.close()
        dbDir.deleteRecursively()
    }

    @Test
    fun chatSessionsRemoveSessionsWithoutMessages() =
        runTest {
            val sessions = RoomChatSessionStore(db)
            sessions.save(
                listOf(
                    AiChatSession("empty", "New chat", 2L),
                    AiChatSession("full", "Saved chat", 1L),
                ),
            )
            RoomChatHistoryProvider(db).store(
                "full",
                listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)),
            )

            val remaining = AiFacadeImpl(db) { AiConfig() }.chatSessions()

            remaining.map { it.id } shouldContainExactly listOf("full")
            sessions.load().map { it.id } shouldContainExactly listOf("full")
        }

    @Test
    fun chatHistorySkipsToolResultsInsteadOfReturningBlankUserTurns() =
        runTest {
            RoomChatHistoryProvider(db).store(
                "tool-history",
                listOf(
                    Message.User("find tax", RequestMetaInfo.Empty),
                    Message.Assistant(
                        MessagePart.Tool.Call("call-1", "search_documents", "{\"query\":\"tax\"}"),
                        ResponseMetaInfo.Empty,
                    ),
                    Message.User(
                        MessagePart.Tool.Result("call-1", "search_documents", "found one"),
                        RequestMetaInfo.Empty,
                    ),
                ),
            )

            val facade = AiFacadeImpl(db) { AiConfig() }
            facade.chatHistoryFor("tool-history") shouldBe
                listOf(AiChatTurn(AiRole.USER, "find tax"))
            facade.chatTranscriptFor("tool-history") shouldContainExactly
                listOf(
                    AiChatHistoryItem.UserTurn("find tax"),
                    AiChatHistoryItem.ToolUse("search_documents", "{\"query\":\"tax\"}", "found one"),
                )
        }
}
