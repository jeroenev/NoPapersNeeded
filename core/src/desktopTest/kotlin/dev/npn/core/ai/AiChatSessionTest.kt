package dev.npn.core.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import io.kotest.matchers.collections.shouldContainExactly
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
}
