@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class, androidx.compose.ui.InternalComposeUiApi::class)

package dev.npn.app.ui.ai

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import dev.npn.app.FakeLibrarySession
import dev.npn.app.aiConfiguredSettings
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.ai.AiChatHistoryItem
import dev.npn.core.ai.AiChatSession
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import java.util.Locale
import javax.swing.SwingUtilities
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/** The chat transcript against a real composition. */
class AiChatScreenTest {
    private var previousLocale: Locale? = null

    @BeforeTest
    fun pinEnglishLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restoreLocale() {
        previousLocale?.let { Locale.setDefault(it) }
    }

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

    private fun ImageComposeScene.input(): SemanticsNode =
        nodes().first { it.config.getOrNull(SemanticsProperties.EditableText) != null }

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

    private fun scene(session: FakeLibrarySession): ImageComposeScene {
        val lifecycle =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        val viewModels =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        return ImageComposeScene(WIDTH, HEIGHT) {
            CompositionLocalProvider(
                LocalLifecycleOwner provides lifecycle,
                LocalViewModelStoreOwner provides viewModels,
            ) {
                NpnTheme(darkTheme = false) {
                    AiChatScreen(
                        session = session,
                        settings = aiConfiguredSettings(),
                        onBack = {},
                        onOpenDoc = {},
                        onOpenFiltered = {},
                        onAiEditDocs = {},
                        showAiEdit = false,
                        onAiEdit = {},
                    )
                }
            }
        }
    }

    private fun SemanticsNode.contentDescription(): String? =
        config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()

    private fun SemanticsNode.text(): String? =
        config
            .getOrNull(SemanticsProperties.Text)
            ?.firstOrNull()
            ?.text

    private fun ImageComposeScene.has(match: (SemanticsNode) -> Boolean): Boolean = nodes().any(match)

    /** Click the nearest clickable node at or above the first node [match] finds. */
    private fun ImageComposeScene.click(match: (SemanticsNode) -> Boolean) {
        generateSequence(nodes().first(match)) { it.parent }
            .first { it.config.getOrNull(SemanticsActions.OnClick) != null }
            .config[SemanticsActions.OnClick]
            .action
            ?.invoke()
    }

    private fun ImageComposeScene.nodes(): List<SemanticsNode> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        return semanticsOwners.flatMap { descendants(it.rootSemanticsNode) }
    }

    private fun onUiThread(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { runCatching(block).onFailure { failure = it } }
        failure?.let { throw it }
    }

    private companion object {
        const val WIDTH = 600
        const val HEIGHT = 900
    }
}
