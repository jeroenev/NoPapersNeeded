package dev.npn.core.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import dev.npn.core.db.NpnDatabase
import dev.npn.core.db.npnDatabase
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.assertFails
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
    fun chatSessionsListOnlyConversationsAndNeverWrite() =
        runTest {
            val sessions = RoomChatSessionStore(db)
            sessions.save(
                listOf(
                    AiChatSession("empty", null, 2L),
                    AiChatSession("full", "Saved chat", 1L),
                ),
            )
            RoomChatHistoryProvider(db).store(
                "full",
                listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)),
            )

            val remaining = AiFacadeImpl(db) { AiConfig() }.chatSessions()

            remaining.map { it.id } shouldContainExactly listOf("full")
            // A read leaves the store as it found it.
            sessions.load().map { it.id } shouldContainExactly listOf("empty", "full")
        }

    @Test
    fun aStoredEnglishPlaceholderTitleReadsAsUntitled() =
        runTest {
            RoomChatSessionStore(db).save(
                listOf(
                    AiChatSession("a", "New chat", 2L),
                    AiChatSession("b", "Assistant chat", 1L),
                    AiChatSession("c", "Electricity bills", 0L),
                ),
            )
            val history = RoomChatHistoryProvider(db)
            listOf("a", "b", "c").forEach {
                history.store(it, listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)))
            }

            AiFacadeImpl(db) { AiConfig() }.chatSessions().map { it.title } shouldContainExactly
                listOf(null, null, "Electricity bills")
        }

    @Test
    fun aNewSessionLeavesNothingBehindUntilItIsUsed() =
        runTest {
            val facade = AiFacadeImpl(db) { AiConfig() }

            val created = facade.createChatSession()

            created.title shouldBe null
            facade.chatSessions() shouldBe emptyList()
            RoomChatSessionStore(db).load() shouldBe emptyList()
        }

    /** The question titles the session before the endpoint is asked, so even a failed ask names it. */
    @Test
    fun askingTitlesTheSessionFromItsFirstQuestion() =
        runTest {
            val facade = AiFacadeImpl(db) { AiConfig() }
            val session = facade.createChatSession()

            // No endpoint is configured, so each ask fails right after it touches the session.
            assertFails { facade.ask(session.id, "  Which   invoices are\noverdue?  ").collect() }
            assertFails { facade.ask(session.id, "And last year?").collect() }

            facade.chatSessions().single().title shouldBe "Which invoices are overdue?"
        }

    @Test
    fun concurrentAsksKeepEverySession() =
        runTest {
            val facade = AiFacadeImpl(db) { AiConfig() }

            (1..SESSIONS)
                .map { i -> async { assertFails { facade.ask("s$i", "question $i").collect() } } }
                .awaitAll()

            facade.chatSessions().map { it.id }.toSet() shouldBe (1..SESSIONS).map { "s$it" }.toSet()
        }

    @Test
    fun everyStoreWriteRunsAsOwnedWork() =
        runTest {
            var owned = 0
            val ownWrite: suspend (suspend () -> Unit) -> Unit = { write ->
                owned++
                write()
            }
            val facade = AiFacadeImpl(db, ownWrite = ownWrite) { AiConfig() }

            assertFails { facade.ask("s1", "question").collect() }
            facade.clearChatSession("s1")
            facade.deleteChatSession("s1")

            // ask stores the session; clear and delete each remove the history and store the list.
            owned shouldBe 5
        }

    @Test
    fun chatTranscriptRendersToolResultsInsteadOfBlankUserTurns() =
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
            facade.chatTranscriptFor("tool-history") shouldContainExactly
                listOf(
                    AiChatHistoryItem.UserTurn("find tax"),
                    AiChatHistoryItem.ToolUse("search_documents", "{\"query\":\"tax\"}", "found one"),
                )
        }

    private companion object {
        const val SESSIONS = 20
    }
}
