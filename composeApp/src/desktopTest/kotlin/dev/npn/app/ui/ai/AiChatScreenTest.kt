@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui.ai

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.text.AnnotatedString
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.aiConfiguredSettings
import dev.npn.app.ui.click
import dev.npn.app.ui.contentDescription
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.isEditable
import dev.npn.app.ui.node
import dev.npn.app.ui.onUiThread
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
            val scene = scene(session)
            try {
                scene.render()
                scene.click { it.contentDescription() == "Expand" }
                scene.render()
                scene.has { it.contentDescription() == "Collapse" } shouldBe true

                scene.click { it.contentDescription() == "Chat history" }
                scene.render()
                scene.click { it.text() == second.title }
                scene.render()

                scene.has { it.contentDescription() == "Collapse" } shouldBe false
            } finally {
                scene.close()
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
            val scene = scene(session)
            try {
                scene.render()

                scene.has { it.text()?.contains("\"invoices\"") == true } shouldBe true
            } finally {
                scene.close()
            }
        }

    /** Enter in the question field sends it; Shift+Enter leaves it there for another line. */
    @Test
    fun enterSendsTheQuestionAndShiftEnterDoesNot() =
        onUiThread {
            val session = FakeLibrarySession()
            val scene = scene(session)
            try {
                scene.render()
                scene
                    .input()
                    .config[SemanticsActions.RequestFocus]
                    .action
                    ?.invoke()
                scene
                    .input()
                    .config[SemanticsActions.SetText]
                    .action
                    ?.invoke(AnnotatedString("What is due?"))
                scene.render()

                scene.press(Key.Enter, shift = true)
                scene.render()
                session.aiChat.asked shouldBe emptyList()

                scene.press(Key.Enter)
                scene.render()
                session.aiChat.asked.map { it.second } shouldContainExactly listOf("What is due?")
            } finally {
                scene.close()
            }
        }

    private fun ImageComposeScene.input(): SemanticsNode = node { it.isEditable() }

    /** Sent to whatever holds the keyboard; the key-event constructor is Compose's internal one. */
    private fun ImageComposeScene.press(
        key: Key,
        shift: Boolean = false,
    ) {
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyDown, isShiftPressed = shift))
        sendKeyEvent(KeyEvent(key, KeyEventType.KeyUp, isShiftPressed = shift))
    }

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
