@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.nav

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigationevent.DirectNavigationEventInput
import androidx.navigationevent.NavigationEventDispatcher
import androidx.navigationevent.NavigationEventDispatcherOwner
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
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
import dev.npn.app.ui.nodes
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.pressConsumed
import dev.npn.app.ui.texts
import dev.npn.app.ui.theme.NpnTheme
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.fail

/**
 * Back from the compact document detail, as the app wires it: one Back leaves a detail whose peek
 * sheet is collapsed, and an expanded sheet takes the first Back to collapse.
 *
 * The detail focuses its preview once the document is in (for the viewer's keyboard shortcuts), and
 * the shell around every screen listens for its own shortcuts. A platform hands Back to the focused
 * element before the back dispatcher: desktop's Escape, and on some phones (OxygenOS) the back
 * gesture itself, arrive as a key. Compose turns an unconsumed Back or Escape key into a focus exit,
 * so a focusable around the preview would spend the first Back moving focus out to it.
 */
class DetailBackTest : EnglishLocaleTest() {
    private val scope = CoroutineScope(Job())
    private val session = FakeLibrarySession()

    @AfterTest
    fun restore() {
        scope.cancel()
    }

    @Test
    fun oneBackKeyLeavesTheDetailOpenedFromTheLibrary() =
        app {
            openFromLibrary()

            back(Key.Back)

            screens() shouldBe listOf("Library")
        }

    @Test
    fun oneEscapeLeavesTheDetail() =
        app {
            openFromLibrary()

            back(Key.Escape)

            screens() shouldBe listOf("Library")
        }

    /** After moving to the next document in the pager, one Back still leaves the detail. */
    @Test
    fun oneBackKeyLeavesTheDetailAfterMovingToAnotherDocument() =
        app {
            openFromLibrary()
            ui { scene.click { it.contentDescription() == "Next document" } }
            await("the next document") { scene.has { it.texts().contains("2 of 3") } }

            back(Key.Back)

            screens() shouldBe listOf("Library")
        }

    /** An expanded peek sheet takes the first Back to collapse; the second leaves. */
    @Test
    fun theFirstBackCollapsesAnExpandedSheetAndTheSecondLeaves() =
        app {
            openFromLibrary()
            ui { scene.click { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show details" } }
            await("the expanded sheet") {
                scene.has { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Hide details" }
            }

            back(Key.Back)

            screens() shouldBe listOf("Library", "DocDetail")
            ui { scene.has { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Show details" } } shouldBe true

            back(Key.Back)

            screens() shouldBe listOf("Library")
        }

    @Test
    fun theTopBarBackLeavesTheDetail() =
        app {
            openFromLibrary()

            ui { scene.click { it.contentDescription() == "Back" } }
            settle()

            screens() shouldBe listOf("Library")
        }

    private fun app(block: App.() -> Unit) {
        val app = onUiThreadResult { App() }
        try {
            app.block()
        } finally {
            onUiThread { app.close() }
        }
    }

    /** The library's screens in a compact window, as the app shows them, with three documents. */
    private inner class App {
        private var shown: LibraryUi? = null
        private var time = 0L
        private val settings = FakeSettingsRepository().apply { updateSettings { it.copy(updateCheckEnabled = false) } }

        /** The platform's back: what a Back or Escape key no element consumed becomes. */
        private val dispatcher = NavigationEventDispatcher()
        private val backInput = DirectNavigationEventInput().also(dispatcher::addInput)
        private val backOwner =
            object : NavigationEventDispatcherOwner {
                override val navigationEventDispatcher = dispatcher
            }

        val scene: ImageComposeScene =
            hostedScene(WIDTH, HEIGHT, LocalNavigationEventDispatcherOwner provides backOwner) {
                LocalizedApp(null, session, scope) { library ->
                    shown = library
                    if (library != null) {
                        NpnTheme(darkTheme = false) {
                            DocumentDraftHost(library.drafts) {
                                MainNav(
                                    settings = settings,
                                    session = session,
                                    library = library,
                                    aiConfigured = false,
                                    queue = QueueStatus.None,
                                    writeBlock = null,
                                    snackbar = remember { SnackbarHostState() },
                                    onOpenLibrary = {},
                                    writeBlockActions = NoBlockActions,
                                )
                            }
                        }
                    }
                }
            }

        private val nav: NavHostController get() = checkNotNull(shown).nav

        /** Tap the first of three documents in the library and wait until its detail shows it. */
        fun openFromLibrary() {
            val items = TITLES.mapIndexed { i, title -> listItem(doc("doc-$i", title = title)) }
            session.documents.documents.value = items
            session.documents.singleDoc.value = items.first()
            await("the library's documents") { scene.has { TITLES.first() in it.texts() } }
            ui { scene.click { TITLES.first() in it.texts() } }
            // Loaded: the pager's position shows, and the preview takes focus a frame later.
            await("the document's detail") { scene.has { "1 of 3" in it.texts() } }
            settle()
            screens() shouldBe listOf("Library", "DocDetail")
        }

        /**
         * Press [key] as a platform delivers Back: to the focused element first, and to the back
         * dispatcher only when nothing consumed the press.
         */
        fun back(key: Key) {
            ui { if (!scene.pressConsumed(key)) backInput.backCompleted() }
            settle()
        }

        /** Render frames on the UI thread, letting the work it queued run in between. */
        fun settle() =
            repeat(SETTLE_ROUNDS) {
                ui { frames() }
                Thread.sleep(POLL_MS)
            }

        /** Render on the UI thread until [predicate] holds there. */
        fun await(
            what: String,
            predicate: () -> Boolean,
        ) {
            val deadline = System.nanoTime() + TIMEOUT_NANOS
            while (System.nanoTime() < deadline) {
                if (ui {
                        frames()
                        predicate()
                    }
                ) {
                    return
                }
                Thread.sleep(POLL_MS)
            }
            val texts = ui { scene.nodes().flatMap { it.texts() + listOfNotNull(it.contentDescription()) } }
            fail("Timed out waiting for $what. The scene shows: $texts")
        }

        /** The screens on the back stack, bottom first. */
        fun screens(): List<String> =
            ui {
                nav.currentBackStack.value.mapNotNull { entry ->
                    ROUTES.firstOrNull { entry.destination.hasRoute(it) }?.simpleName
                }
            }

        fun <T> ui(block: () -> T): T = onUiThreadResult(block)

        private fun frames() =
            repeat(FRAMES) {
                scene.render(time)
                time += FRAME_NANOS
            }

        fun close() = scene.close()
    }

    private fun <T> onUiThreadResult(block: () -> T): T {
        var result: Result<T>? = null
        onUiThread { result = runCatching(block) }
        return checkNotNull(result).getOrThrow()
    }

    private object NoBlockActions : WriteBlockActions {
        override fun retry() = Unit

        override fun rebuildIndex() = Unit
    }

    private companion object {
        const val WIDTH = 500
        const val HEIGHT = 900
        const val FRAMES = 5
        const val FRAME_NANOS = 100_000_000L
        const val SETTLE_ROUNDS = 4
        const val POLL_MS = 50L
        const val TIMEOUT_NANOS = 15_000_000_000L
        val TITLES = listOf("Electricity invoice", "Water bill", "Lease")
        val ROUTES = listOf(Route.Library::class, Route.DocDetail::class)
    }
}
