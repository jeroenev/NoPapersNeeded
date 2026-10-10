@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app.ui.nav

import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.npn.app.ui.detail.onUiThread
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * The shell's moves between its destinations, on a navigation host with the app's own routes: a
 * destination chosen in the shell is the one shown, whatever the library pushed before.
 */
class ShellNavigationTest {
    /** A banner of the library's opened its screen above it; the Library item returns to the library. */
    @Test
    fun theLibraryItemLeavesAScreenTheLibraryPushed() =
        shell {
            for (pushed in listOf(Route.Queue, Route.Trash)) {
                nav.navigate(pushed)
                settle()
                nav.navigateTopLevel(TopLevelDestination.LIBRARY)
                settle()
                screens() shouldBe listOf("Library")
            }
        }

    @Test
    fun theLibraryItemLeavesItAfterARoundTripThroughTags() =
        shell {
            nav.navigate(Route.Queue)
            settle()
            nav.navigateTopLevel(TopLevelDestination.TAGS)
            settle()
            screens() shouldBe listOf("Library", "TagManagement")

            nav.navigateTopLevel(TopLevelDestination.LIBRARY)
            settle()
            screens() shouldBe listOf("Library")
        }

    /** The assistant's "open all documents" lands on the library it filters. */
    @Test
    fun theAssistantOpensTheLibraryAfterAPushedScreen() =
        shell {
            nav.navigate(Route.Queue)
            settle()
            nav.navigateAi(Route.AiChat, restoreState = true)
            settle()
            screens() shouldBe listOf("Library", "AiChat")

            nav.navigateTopLevel(TopLevelDestination.LIBRARY)
            settle()
            screens() shouldBe listOf("Library")
        }

    /** The library's banners open Trash and Queue as the shell does: Back returns to the library. */
    @Test
    fun aTopLevelScreenSitsOnTheLibraryAndKeepsItsPlace() =
        shell {
            nav.navigateTopLevel(TopLevelDestination.QUEUE)
            settle()
            screens() shouldBe listOf("Library", "Queue")
            nav.popBackStack()
            settle()
            screens() shouldBe listOf("Library")

            nav.navigateTopLevel(TopLevelDestination.TRASH)
            settle()
            nav.navigateTopLevel(TopLevelDestination.LIBRARY)
            settle()
            screens() shouldBe listOf("Library")
            nav.navigateTopLevel(TopLevelDestination.TRASH)
            settle()
            screens() shouldBe listOf("Library", "Trash")
        }

    private fun shell(block: Shell.() -> Unit) =
        onUiThread {
            val shell = Shell()
            try {
                shell.settle()
                shell.block()
            } finally {
                shell.close()
            }
        }

    /** The app's navigation host with stand-ins for its screens, in a window of its own. */
    private class Shell {
        lateinit var nav: NavHostController
        private var time = 0L
        private val viewModels =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        private val lifecycle =
            object : LifecycleOwner {
                override val lifecycle =
                    LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
            }
        private val scene =
            ImageComposeScene(WIDTH, HEIGHT) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides lifecycle,
                    LocalViewModelStoreOwner provides viewModels,
                ) {
                    val controller = rememberNavController()
                    nav = controller
                    NavHost(controller, startDestination = Route.Library) {
                        composable<Route.Library> { Text("Library") }
                        composable<Route.TagManagement> { Text("Tags") }
                        composable<Route.Trash> { Text("Trash") }
                        composable<Route.Queue> { Text("Queue") }
                        composable<Route.Settings> { Text("Settings") }
                        composable<Route.AiChat> { Text("AiChat") }
                        composable<Route.AiEdit> { Text("AiEdit") }
                    }
                }
            }

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
        const val WIDTH = 100
        const val HEIGHT = 100
        const val FRAMES = 20
        const val FRAME_NANOS = 100_000_000L
        val ROUTES =
            listOf(
                Route.Library::class,
                Route.TagManagement::class,
                Route.Trash::class,
                Route.Queue::class,
                Route.Settings::class,
                Route.AiChat::class,
                Route.AiEdit::class,
            )
    }
}
