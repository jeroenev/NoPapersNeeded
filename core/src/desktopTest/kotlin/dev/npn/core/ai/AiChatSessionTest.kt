package dev.npn.core.ai

import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import dev.npn.core.db.TempIndexDb
import dev.npn.core.util.NpnJson
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFails

class AiChatSessionTest {
    private val temp = TempIndexDb("npn-chat-session-test")
    private val db = temp.db
    private val device = temp.deviceDb
    private val chats get() = device.keyValueDao()

    @AfterTest
    fun teardown() = temp.close()

    /**
     * Builds before nullable titles stored a "New chat" session as soon as it was created; one that
     * holds no conversation was never asked in, so it is not listed.
     */
    @Test
    fun anOlderBuildsUnusedSessionIsNotListedAndTheReadNeverWrites() =
        runTest {
            val stored =
                listOf(
                    AiChatSession("unused", "New chat", 3L),
                    AiChatSession("cleared", null, 2L),
                    AiChatSession("full", "Saved chat", 1L),
                )
            ChatSessions(chats).save(stored)
            RoomChatHistoryProvider(chats).store(
                "full",
                listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)),
            )

            val listed = AiFacadeImpl(db, chats) { AiConfig() }.chatSessions()

            listed.map { it.id } shouldContainExactly listOf("cleared", "full")
            // A read leaves the stored list as it found it.
            chats.get(SESSION_LIST_KEY) shouldBe
                NpnJson.Persisted.encodeToString(ListSerializer(AiChatSession.serializer()), stored)
        }

    @Test
    fun aClearedSessionStaysListedUntitledUntilItIsDeleted() =
        runTest {
            val facade = AiFacadeImpl(db, chats) { AiConfig() }
            val session = facade.createChatSession()
            assertFails { facade.ask(session.id, "Which invoices are overdue?").collect() }
            RoomChatHistoryProvider(chats).store(
                session.id,
                listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)),
            )

            facade.clearChatSession(session.id)

            // A reopen lists the sessions afresh, and shows what the open screen showed.
            val reopened = AiFacadeImpl(db, chats) { AiConfig() }
            reopened.chatSessions().map { it.id to it.title } shouldContainExactly listOf(session.id to null)
            reopened.chatTranscriptFor(session.id) shouldBe emptyList()

            assertFails { reopened.ask(session.id, "And last year?").collect() }
            reopened.chatSessions().single().title shouldBe "And last year?"

            reopened.deleteChatSession(session.id)
            reopened.chatSessions() shouldBe emptyList()
        }

    @Test
    fun aClearedPreSessionsChatStaysListedUntitled() =
        runTest {
            RoomChatHistoryProvider(chats).store(
                LEGACY_ID,
                listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)),
            )
            val facade = AiFacadeImpl(db, chats) { AiConfig() }

            facade.clearChatSession(LEGACY_ID)

            AiFacadeImpl(db, chats) { AiConfig() }.chatSessions().map { it.id to it.title } shouldContainExactly
                listOf(LEGACY_ID to null)
        }

    @Test
    fun clearingASessionNeverAskedInLeavesNothingBehind() =
        runTest {
            val facade = AiFacadeImpl(db, chats) { AiConfig() }
            val session = facade.createChatSession()

            facade.clearChatSession(session.id)

            facade.chatSessions() shouldBe emptyList()
        }

    @Test
    fun aStoredEnglishPlaceholderTitleReadsAsUntitled() =
        runTest {
            ChatSessions(chats).save(
                listOf(
                    AiChatSession("a", "New chat", 2L),
                    AiChatSession("b", "Assistant chat", 1L),
                    AiChatSession("c", "Electricity bills", 0L),
                ),
            )
            val history = RoomChatHistoryProvider(chats)
            listOf("a", "b", "c").forEach {
                history.store(it, listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)))
            }

            AiFacadeImpl(db, chats) { AiConfig() }.chatSessions().map { it.title } shouldContainExactly
                listOf(null, null, "Electricity bills")
        }

    /**
     * A session its own first question titled "New chat" keeps that title: only a title the
     * conversation could not have produced reads as an old build's placeholder.
     */
    @Test
    fun aSessionAskedInWithATitleLikeAPlaceholderKeepsIt() =
        runTest {
            ChatSessions(chats).save(
                listOf(
                    AiChatSession("a", "New chat", 2L),
                    AiChatSession("b", "Assistant chat", 1L),
                    AiChatSession("c", "New chat", 0L),
                ),
            )
            val history = RoomChatHistoryProvider(chats)
            history.store(
                "a",
                listOf(
                    Message.User("New chat", RequestMetaInfo.Empty),
                    Message.Assistant("answer", ResponseMetaInfo.Empty),
                ),
            )
            history.store(
                "b",
                listOf(
                    Message.User("Assistant chat", RequestMetaInfo.Empty),
                    Message.Assistant("answer", ResponseMetaInfo.Empty),
                ),
            )
            history.store("c", listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)))

            AiFacadeImpl(db, chats) { AiConfig() }.chatSessions().map { it.title } shouldContainExactly
                listOf("New chat", "Assistant chat", null)
        }

    @Test
    fun aNewSessionLeavesNothingBehindUntilItIsUsed() =
        runTest {
            val facade = AiFacadeImpl(db, chats) { AiConfig() }

            val created = facade.createChatSession()

            created.title shouldBe null
            facade.chatSessions() shouldBe emptyList()
            ChatSessions(chats).load() shouldBe emptyList()
        }

    /** The question titles the session before the endpoint is asked, so even a failed ask names it. */
    @Test
    fun askingTitlesTheSessionFromItsFirstQuestion() =
        runTest {
            val facade = AiFacadeImpl(db, chats) { AiConfig() }
            val session = facade.createChatSession()

            // No endpoint is configured, so each ask fails right after it touches the session.
            assertFails { facade.ask(session.id, "  Which   invoices are\noverdue?  ").collect() }
            assertFails { facade.ask(session.id, "And last year?").collect() }

            facade.chatSessions().single().title shouldBe "Which invoices are overdue?"
        }

    /** A user upgrading from a build without sessions asks in the new session the screen opens on. */
    @Test
    fun thePreSessionsChatStaysListedAfterAnotherSessionIsAskedIn() =
        runTest {
            RoomChatHistoryProvider(chats).store(
                LEGACY_ID,
                listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)),
            )
            val facade = AiFacadeImpl(db, chats) { AiConfig() }
            val fresh = facade.createChatSession()

            assertFails { facade.ask(fresh.id, "Which invoices are overdue?").collect() }

            facade.chatSessions().map { it.id to it.title } shouldContainExactly
                listOf(fresh.id to "Which invoices are overdue?", LEGACY_ID to null)
            // A reopen reads the same list: nothing about it lived in memory.
            AiFacadeImpl(db, chats) { AiConfig() }.chatSessions().map { it.id } shouldContainExactly
                listOf(fresh.id, LEGACY_ID)
        }

    @Test
    fun askingInThePreSessionsChatStoresItTitledByTheQuestion() =
        runTest {
            RoomChatHistoryProvider(chats).store(
                LEGACY_ID,
                listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)),
            )
            val facade = AiFacadeImpl(db, chats) { AiConfig() }

            assertFails { facade.ask(LEGACY_ID, "And last year?").collect() }

            facade.chatSessions().map { it.id to it.title } shouldContainExactly listOf(LEGACY_ID to "And last year?")
            ChatSessions(chats).load().map { it.id } shouldContainExactly listOf(LEGACY_ID)
        }

    @Test
    fun deletingThePreSessionsChatRemovesItForGood() =
        runTest {
            RoomChatHistoryProvider(chats).store(
                LEGACY_ID,
                listOf(Message.Assistant("answer", ResponseMetaInfo.Empty)),
            )
            val facade = AiFacadeImpl(db, chats) { AiConfig() }
            assertFails { facade.ask("other", "Which invoices are overdue?").collect() }

            facade.deleteChatSession(LEGACY_ID)

            AiFacadeImpl(db, chats) { AiConfig() }.chatSessions().map { it.id } shouldContainExactly listOf("other")
        }

    @Test
    fun concurrentAsksKeepEverySession() =
        runTest {
            val facade = AiFacadeImpl(db, chats) { AiConfig() }

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
            val facade = AiFacadeImpl(db, chats, ownWrite = ownWrite) { AiConfig() }

            assertFails { facade.ask("s1", "question").collect() }
            facade.clearChatSession("s1")
            facade.deleteChatSession("s1")

            // ask stores the session; clear and delete each remove the history and store the list.
            owned shouldBe 5
        }

    @Test
    fun chatTranscriptRendersToolResultsInsteadOfBlankUserTurns() =
        runTest {
            RoomChatHistoryProvider(chats).store(
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

            val facade = AiFacadeImpl(db, chats) { AiConfig() }
            facade.chatTranscriptFor("tool-history") shouldContainExactly
                listOf(
                    AiChatHistoryItem.UserTurn("find tax"),
                    AiChatHistoryItem.ToolUse("search_documents", mapOf("query" to "tax"), "found one"),
                )
        }

    private companion object {
        const val SESSIONS = 20

        /** The conversation id builds from before sessions existed stored their one chat under. */
        const val LEGACY_ID = "main"

        const val SESSION_LIST_KEY = "${RoomChatHistoryProvider.KEY_PREFIX}session-metadata"
    }
}
