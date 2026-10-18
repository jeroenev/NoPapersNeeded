@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.npn.app.ui.closing
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.WindowWidthClass
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.library.LibraryDialog
import dev.npn.app.ui.library.LibraryViewModel
import dev.npn.app.ui.onUiThread
import dev.npn.core.app.LibraryFilter
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * A screen's state is its viewmodel's, which the session's retained holder ([LibrarySessionUi])
 * keeps above [dev.npn.app.ui.common.LocaleProvider], in the back-stack entry the screen is shown
 * in: a change of the app's language, which rebuilds every composable under it, and a change of
 * width class, which swaps the layout around the screen, both find the screen as the user left it.
 */
class RetainedScreenStateTest : EnglishLocaleTest() {
    private val scope = CoroutineScope(Job())
    private val session = FakeLibrarySession()
    private var language by mutableStateOf<String?>(null)
    private var widthClass by mutableStateOf(WindowWidthClass.WIDE)
    private var screen: LibraryViewModel? = null

    @AfterTest
    fun restore() {
        scope.cancel()
    }

    @Test
    fun aLanguageChangeKeepsWhatTheScreenIsInTheMiddleOf() =
        afterTheUserLeftTheLibraryMidWay { scene ->
            language = "nl"
            scene.settle()
        }

    @Test
    fun aWidthClassChangeKeepsWhatTheScreenIsInTheMiddleOf() =
        afterTheUserLeftTheLibraryMidWay { scene ->
            widthClass = WindowWidthClass.COMPACT
            scene.settle()
        }

    /**
     * Open the library's screen, select, search, preview and open a dialog, then [change] the window:
     * the screen shows the same viewmodel, with all of it, while what the language keys was rebuilt.
     */
    private fun afterTheUserLeftTheLibraryMidWay(change: (ImageComposeScene) -> Unit) =
        onUiThread {
            var subtree: Any? = null
            hostedScene(WIDTH, HEIGHT) {
                LocalizedApp(language, session, scope) { library ->
                    subtree = remember { Any() }
                    if (library != null) {
                        CompositionLocalProvider(LocalWindowWidthClass provides widthClass) {
                            LibraryViewModelHost(library) { Screens(library) }
                        }
                    }
                }
            }.closing {
                settle()
                val before = checkNotNull(screen)
                before.apply {
                    setFilter(LibraryFilter(query = "tax", tagsAll = listOf("invoice")))
                    startSelection("doc-1")
                    preview("doc-1")
                    openDialog(LibraryDialog.RenameView("v1", "Invoices"))
                }
                settle()
                val subtreeBefore = subtree

                change(this)

                screen shouldBeSameInstanceAs before
                before.ui.value.filter shouldBe LibraryFilter(query = "tax", tagsAll = listOf("invoice"))
                before.ui.value.selected shouldBe setOf("doc-1")
                before.preview.value shouldBe "doc-1"
                before.dialog.value shouldBe LibraryDialog.RenameView("v1", "Invoices")
                if (language != null) subtree shouldNotBeSameInstanceAs subtreeBefore
            }
        }

    /** The library's nav graph, laid out one way on a wide window and another on a compact one. */
    @Composable
    private fun Screens(library: LibraryUi) {
        val host: @Composable () -> Unit = {
            NavHost(library.nav, startDestination = LIBRARY) {
                composable(LIBRARY) {
                    screen =
                        viewModel {
                            LibraryViewModel(session, FakeSettingsRepository(), createSavedStateHandle())
                        }
                }
            }
        }
        if (widthClass == WindowWidthClass.WIDE) Row { host() } else Column { host() }
    }

    private fun ImageComposeScene.settle() = repeat(FRAMES) { render() }

    private companion object {
        const val WIDTH = 100
        const val HEIGHT = 100
        const val FRAMES = 3
        const val LIBRARY = "library"
    }
}
