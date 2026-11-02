@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.nav

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import dev.npn.app.EnglishLocaleTest
import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.LibraryUi
import dev.npn.app.LocalizedApp
import dev.npn.app.ui.click
import dev.npn.app.ui.contentDescription
import dev.npn.app.ui.detail.DocumentDraftHost
import dev.npn.app.ui.has
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.press
import dev.npn.app.ui.texts
import dev.npn.app.ui.theme.NpnTheme
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The open library's navigation as the app wires it: a tap navigates once, however quickly a second
 * one follows it. The second click of each pair lands before a frame has shown the first one's
 * result, on the screen the first one left. Every screen's navigation goes through the same guard
 * (`MainNavGraph`'s `from`), so a pop and a push stand for the rest.
 */
class MainNavTest : EnglishLocaleTest() {
    private val scope = CoroutineScope(Job())
    private val session = FakeLibrarySession()

    @AfterTest
    fun restore() {
        scope.cancel()
    }

    /** Back stack `[Library, AiChat]`: a double tap pops the assistant, not the library under it. */
    @Test
    fun aDoubleTapOnBackPopsOneScreen() =
        app {
            nav.navigate(Route.AiChat)
            settle()

            scene.click { it.contentDescription() == "Back" }
            scene.click { it.contentDescription() == "Back" }
            settle()

            screens() shouldBe listOf("Library")
        }

    /** On a compact window a settings category is a screen of its own: a double tap opens it once. */
    @Test
    fun aDoubleTapOnASettingsCategoryOpensItOnce() =
        app {
            nav.navigate(Route.Settings())
            settle()

            scene.click { "About" in it.texts() }
            scene.click { "About" in it.texts() }
            settle()

            screens() shouldBe listOf("Library", "Settings", "Settings")
        }

    /** The shortcut list is one tap behind "More" on a compact window, and lists what the screens bind. */
    @Test
    fun theShortcutListOpensFromTheMoreSheet() =
        app {
            scene.click { "More" in it.texts() }
            settle()
            scene.click { "Keyboard shortcuts" in it.texts() }
            settle()

            scene.has { "Everywhere" in it.texts() } shouldBe true
            scene.has { "Search the library." in it.texts() } shouldBe true
        }

    /**
     * Ctrl+/ opens it from the keyboard, also on a screen that focuses nothing of its own (Activity):
     * the shell holds focus then.
     */
    @Test
    fun ctrlSlashOpensTheShortcutList() =
        app {
            scene.click { "Activity" in it.texts() }
            settle()
            scene.press(Key.Slash, ctrl = true)
            settle()

            scene.has { "Everywhere" in it.texts() } shouldBe true

            scene.click { "Close" in it.texts() }
            settle()
            scene.has { "Everywhere" in it.texts() } shouldBe false
        }

    private fun app(block: App.() -> Unit) =
        onUiThread {
            val app = App()
            try {
                app.settle()
                app.block()
            } finally {
                app.close()
            }
        }

    /** The library's screens in a compact window, as the app shows them. */
    private inner class App {
        private var shown: LibraryUi? = null
        private var time = 0L
        private val settings = FakeSettingsRepository().apply { updateSettings { it.copy(updateCheckEnabled = false) } }
        val scene =
            hostedScene(WIDTH, HEIGHT) {
                LocalizedApp(null, session, scope) { library ->
                    shown = library
                    if (library != null) {
                        NpnTheme(darkTheme = false) {
                            DocumentDraftHost(library.drafts) {
                                MainNav(
                                    settings = settings,
                                    session = session,
                                    library = library,
                                    aiConfigured = true,
                                    queue = QueueStatus.None,
                                    writeBlock = null,
                                    snackbar = remember { SnackbarHostState() },
                                    onOpenLibrary = {},
                                    writeBlockActions = NoWriteBlockActions,
                                )
                            }
                        }
                    }
                }
            }

        val nav: NavHostController get() = checkNotNull(shown).nav

        /** Render frames until every transition has run its course. */
        fun settle() =
            repeat(FRAMES) {
                scene.render(time)
                time += FRAME_NANOS
            }

        /** The screens on the back stack, bottom first. */
        fun screens(): List<String> =
            nav.currentBackStack.value.mapNotNull { entry ->
                ROUTES.firstOrNull { entry.destination.hasRoute(it) }?.simpleName
            }

        fun close() = scene.close()
    }

    private companion object {
        const val WIDTH = 500
        const val HEIGHT = 900
        const val FRAMES = 20
        const val FRAME_NANOS = 100_000_000L
        val ROUTES = listOf(Route.Library::class, Route.Settings::class, Route.AiChat::class)
    }
}

private object NoWriteBlockActions : WriteBlockActions {
    override fun retry() = Unit

    override fun rebuildIndex() = Unit
}
