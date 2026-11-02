@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.ai

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.ui.click
import dev.npn.app.ui.closing
import dev.npn.app.ui.contentDescription
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.isEditable
import dev.npn.app.ui.node
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.press
import dev.npn.app.ui.text
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.ai.AiChatHistoryItem
import dev.npn.core.ai.AiChatSession
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The chat transcript against a real composition. */
class AiChatScreenTest : EnglishLocaleTest() {
    /**
     * A tool row expanded in one chat must not show up expanded in the next: the row at the same
     * position there is a different tool use.
     */
    @Test
    fun anExpandedToolRowStaysWithItsChat() =
        onUiThread {
            val first = AiChatSession("a", "Taxes", 2L)
            val second = AiChatSession("b", "Insurance", 1L)
            val session =
                FakeLibrarySession().apply {
                    aiChat.createdChatSession = first
                    aiChat.savedChatSessions = listOf(first, second)
                    aiChat.chatTranscripts =
                        mapOf(
                            first.id to toolChat("tax"),
                            second.id to toolChat("insurance"),
                        )
                }
            scene(session).closing {
                render()
                click { it.contentDescription() == "Expand" }
                render()
                has { it.contentDescription() == "Collapse" } shouldBe true

                click { it.contentDescription() == "Chat history" }
                render()
                click { it.text() == second.title }
                render()

                has { it.contentDescription() == "Collapse" } shouldBe false
            }
        }

    /** Tool arguments are model output: a row labels them as leniently as the tool read them. */
    @Test
    fun aToolRowLabelsArgumentsWithUnquotedKeys() =
        onUiThread {
            val chat = AiChatSession("a", "Invoices", 1L)
            val session =
                FakeLibrarySession().apply {
                    aiChat.createdChatSession = chat
                    aiChat.savedChatSessions = listOf(chat)
                    aiChat.chatTranscripts =
                        mapOf(
                            chat.id to
                                listOf(
                                    AiChatHistoryItem.UserTurn("my invoices"),
                                    AiChatHistoryItem.ToolUse(
                                        "search_documents",
                                        mapOf("query" to "invoices"),
                                        "found one",
                                    ),
                                ),
                        )
                }
            scene(session).closing {
                render()

                has { it.text()?.contains("“invoices”") == true } shouldBe true
            }
        }

    /** Enter in the question field sends it; Shift+Enter leaves it there for another line. */
    @Test
    fun enterSendsTheQuestionAndShiftEnterDoesNot() =
        onUiThread {
            val session = FakeLibrarySession()
            scene(session).closing {
                render()
                input()
                    .config[SemanticsActions.RequestFocus]
                    .action
                    ?.invoke()
                input()
                    .config[SemanticsActions.SetText]
                    .action
                    ?.invoke(AnnotatedString("What is due?"))
                render()

                press(Key.Enter, shift = true)
                render()
                session.aiChat.asked shouldBe emptyList()

                press(Key.Enter)
                render()
                session.aiChat.asked.map { it.second } shouldContainExactly listOf("What is due?")
            }
        }

    /** Without an AI endpoint the chat offers the way to configure one instead of a question field. */
    @Test
    fun anUnconfiguredChatPointsToSettingsInsteadOfTheInput() =
        onUiThread {
            scene(FakeLibrarySession(), configured = false).closing {
                render()

                has { it.text() == "Set up the assistant in Settings" } shouldBe true
                has { it.isEditable() } shouldBe false
            }
        }

    /** A field search names the field by its label and what it looked for, not the tool's keys. */
    @Test
    fun aFieldSearchRowNamesTheFieldByItsLabel() =
        onUiThread {
            val chat = AiChatSession("a", "Letters", 1L)
            val session =
                FakeLibrarySession().apply {
                    aiChat.createdChatSession = chat
                    aiChat.savedChatSessions = listOf(chat)
                    aiChat.chatTranscripts =
                        mapOf(
                            chat.id to
                                listOf(
                                    AiChatHistoryItem.UserTurn("letters from Acme"),
                                    AiChatHistoryItem.ToolUse("list_fields", emptyMap(), "fields"),
                                    AiChatHistoryItem.ToolUse(
                                        "search_fields",
                                        mapOf("field" to "correspondent", "value" to "Acme"),
                                        "found one",
                                    ),
                                ),
                        )
                }
            scene(session).closing {
                render()

                has { it.text() == "Listed fields" } shouldBe true
                has { it.text() == "Searched Correspondent for “Acme”" } shouldBe true
            }
        }

    /** A document a tool read is named by its title once the library has it, not by its hash. */
    @Test
    fun aReadDocumentRowNamesTheDocumentByItsTitle() =
        onUiThread {
            val chat = AiChatSession("a", "Bills", 1L)
            val docId = "0123456789abcdef"
            val session =
                FakeLibrarySession().apply {
                    aiChat.createdChatSession = chat
                    aiChat.savedChatSessions = listOf(chat)
                    aiChat.chatTranscripts =
                        mapOf(
                            chat.id to
                                listOf(
                                    AiChatHistoryItem.UserTurn("my bill"),
                                    AiChatHistoryItem.ToolUse("get_document", mapOf("docId" to docId), "text"),
                                ),
                        )
                    documents.singleDoc.value = listItem(doc(docId, title = "Electricity bill"))
                }
            scene(session).closing {
                render()
                render()

                has { it.text() == "Read document Electricity bill" } shouldBe true
            }
        }

    /** Deleting a chat cannot be taken back, so it asks first; only the confirmation deletes it. */
    @Test
    fun deletingAChatAsksFirst() =
        onUiThread {
            val first = AiChatSession("a", "Taxes", 2L)
            val second = AiChatSession("b", "Insurance", 1L)
            val session =
                FakeLibrarySession().apply {
                    aiChat.createdChatSession = first
                    aiChat.savedChatSessions = listOf(first, second)
                }
            scene(session).closing {
                render()
                click { it.contentDescription() == "Chat history" }
                render()
                click { it.contentDescription() == "Delete chat" }
                render()
                has { it.text() == "Delete this chat?" } shouldBe true
                session.aiChat.savedChatSessions shouldContainExactly listOf(first, second)

                click { it.text() == "Delete" }
                render()
                render()
                session.aiChat.savedChatSessions.size shouldBe 1
            }
        }

    /** Clearing the conversation asks first too; Cancel keeps it. */
    @Test
    fun clearingTheConversationAsksFirst() =
        onUiThread {
            val chat = AiChatSession("a", "Taxes", 1L)
            val session =
                FakeLibrarySession().apply {
                    aiChat.createdChatSession = chat
                    aiChat.savedChatSessions = listOf(chat)
                    aiChat.chatTranscripts = mapOf(chat.id to toolChat("tax"))
                }
            scene(session).closing {
                render()
                click { it.contentDescription() == "Clear conversation" }
                render()
                has { it.text() == "Clear this conversation?" } shouldBe true

                click { it.text() == "Cancel" }
                render()
                session.aiChat.cleared shouldBe emptyList()

                click { it.contentDescription() == "Clear conversation" }
                render()
                click { it.text() == "Clear" }
                render()
                session.aiChat.cleared shouldContainExactly listOf(chat.id)
            }
        }

    private fun ImageComposeScene.input(): SemanticsNode = node { it.isEditable() }

    private fun toolChat(query: String) =
        listOf(
            AiChatHistoryItem.UserTurn("about $query"),
            AiChatHistoryItem.ToolUse("search_documents", mapOf("query" to query), "found one"),
        )

    private fun scene(
        session: FakeLibrarySession,
        configured: Boolean = true,
    ): ImageComposeScene =
        hostedScene(WIDTH, HEIGHT) {
            NpnTheme(darkTheme = false) {
                AiChatScreen(
                    session = session,
                    configured = configured,
                    onBack = {},
                    onOpenDoc = {},
                    onShowInLibrary = {},
                    onAiEditDocs = {},
                    onBulkEdit = {},
                )
            }
        }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 900
    }
}
