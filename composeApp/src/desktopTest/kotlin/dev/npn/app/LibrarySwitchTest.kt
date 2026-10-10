@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.npn.app.ui.detail.onUiThread
import dev.npn.core.app.LibrarySession
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * A library's screens belong to its session: switching to another library, straight or through
 * none, leaves nothing of them running, while a change of the app's language keeps them as they are.
 */
class LibrarySwitchTest {
    private var previousLocale: Locale? = null
    private val scope = CoroutineScope(Job())
    private val settings = FakeSettingsRepository()
    private val first = FakeLibrarySession()
    private var session by mutableStateOf<LibrarySession?>(first)

    /** A screen's viewmodel, which says whether it was cleared. */
    private class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    @BeforeTest
    fun pinEnglishLocale() {
        previousLocale = Locale.getDefault()
        Locale.setDefault(Locale.ENGLISH)
    }

    @AfterTest
    fun restore() {
        scope.cancel()
        previousLocale?.let { Locale.setDefault(it) }
    }

    @Test
    fun aSwitchStraightToAnotherLibraryClearsTheOldLibrarysScreens() {
        val (before, after, probe) =
            afterVisitingADocument { window, _ ->
                session = FakeLibrarySession()
                window.settle()
            }
        probe.cleared shouldBe true
        after.nav shouldNotBeSameInstanceAs before.nav
        after.nav.routes() shouldBe listOf(LIBRARY)
    }

    @Test
    fun aSwitchThroughNoLibraryClearsTheOldLibrarysScreens() {
        val (before, after, _) =
            afterVisitingADocument { window, probe ->
                session = null
                window.settle()
                probe.cleared shouldBe true
                session = FakeLibrarySession()
                window.settle()
            }
        after.nav shouldNotBeSameInstanceAs before.nav
        after.nav.routes() shouldBe listOf(LIBRARY)
    }

    @Test
    fun aLanguageChangeKeepsTheLibrarysScreens() {
        val (before, after, probe) =
            afterVisitingADocument { window, _ ->
                settings.updateSettings { it.copy(appLanguage = "nl") }
                window.settle()
            }
        probe.cleared shouldBe false
        after.nav shouldBeSameInstanceAs before.nav
        after.nav.routes() shouldBe listOf(LIBRARY, DOCUMENT)
    }

    /**
     * An activity recreated for a configuration change builds its UI again from its saved state,
     * while its viewmodels stay: the library's screens are back where they were, with theirs.
     */
    @Test
    fun aRecreatedWindowKeepsTheLibrarysScreens() {
        var probes: List<Probe> = emptyList()
        val (_, after, probe) =
            afterVisitingADocument { window, _ ->
                window.recreate()
                probes = window.probes
            }
        probe.cleared shouldBe false
        after.nav.routes() shouldBe listOf(LIBRARY, DOCUMENT)
        probes.distinct() shouldBe listOf(probe)
    }

    /** What [change] left: the library's UI before and after it, and the document screen's viewmodel. */
    private data class Outcome(
        val before: LibraryUi,
        val after: LibraryUi,
        val probe: Probe,
    )

    /** Open [first], go from its library screen to a document, then [change] something. */
    private fun afterVisitingADocument(change: (Window, Probe) -> Unit): Outcome {
        var outcome: Outcome? = null
        onUiThread {
            val window = Window()
            try {
                window.settle()
                val before = checkNotNull(window.shown)
                before.nav.navigate(DOCUMENT)
                window.settle()
                val probe = window.probes.single()
                change(window, probe)
                outcome = Outcome(before, checkNotNull(window.shown), probe)
            } finally {
                window.close()
            }
        }
        return checkNotNull(outcome)
    }

    /**
     * The app's window, showing the open library's screens. Its viewmodels outlive a [recreate], as
     * an activity's do; its composition and saved state are the activity's own.
     */
    private inner class Window {
        var shown: LibraryUi? = null
        val probes = mutableListOf<Probe>()
        private val viewModels =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        private var registry = SaveableStateRegistry(null) { true }
        private var scene = compose()

        fun settle() = repeat(FRAMES) { scene.render() }

        /** Dispose the UI and build it again from what it saved. */
        fun recreate() {
            val saved = registry.performSave()
            scene.close()
            registry = SaveableStateRegistry(saved) { true }
            scene = compose()
            settle()
        }

        fun close() = scene.close()

        private fun compose(): ImageComposeScene {
            val lifecycle =
                object : LifecycleOwner {
                    override val lifecycle =
                        LifecycleRegistry.createUnsafe(this).apply { currentState = Lifecycle.State.RESUMED }
                }
            val registry = registry
            return ImageComposeScene(WIDTH, HEIGHT) {
                CompositionLocalProvider(
                    LocalLifecycleOwner provides lifecycle,
                    LocalViewModelStoreOwner provides viewModels,
                    LocalSaveableStateRegistry provides registry,
                ) { LibraryScreens() }
            }
        }

        @Composable
        private fun LibraryScreens() {
            LocalizedApp(settings, session, scope) { library ->
                shown = library
                if (library != null) {
                    LibraryViewModelHost(library) {
                        NavHost(library.nav, startDestination = LIBRARY) {
                            composable(LIBRARY) { Text(LIBRARY) }
                            composable(DOCUMENT) {
                                val probe = viewModel { Probe() }
                                remember(probe) { probes += probe }
                                Text(DOCUMENT)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun NavHostController.routes() = currentBackStack.value.mapNotNull { it.destination.route }

    private companion object {
        const val WIDTH = 100
        const val HEIGHT = 100
        const val FRAMES = 3
        const val LIBRARY = "library"
        const val DOCUMENT = "document"
    }
}
