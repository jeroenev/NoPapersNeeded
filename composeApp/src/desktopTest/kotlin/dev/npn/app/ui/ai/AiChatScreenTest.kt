@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.ai

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.aiConfiguredSettings
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
                                    AiChatHistoryItem.ToolUse("search_documents", "{query: invoices}", "found one"),
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

    private fun ImageComposeScene.input(): SemanticsNode = node { it.isEditable() }

    private fun toolChat(query: String) =
        listOf(
            AiChatHistoryItem.UserTurn("about $query"),
            AiChatHistoryItem.ToolUse("search_documents", "{\"query\":\"$query\"}", "found one"),
        )

    private fun scene(session: FakeLibrarySession): ImageComposeScene =
        hostedScene(WIDTH, HEIGHT) {
            NpnTheme(darkTheme = false) {
                AiChatScreen(
                    session = session,
                    settings = aiConfiguredSettings(),
                    onBack = {},
                    onOpenDoc = {},
                    onShowInLibrary = {},
                    onAiEditDocs = {},
                    onAiEdit = null,
                )
            }
        }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 900
    }
}
