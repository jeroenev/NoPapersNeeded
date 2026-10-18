@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.npn.app.ui.hostedScene
import dev.npn.app.ui.onUiThread
import dev.npn.app.ui.viewModelStoreOwner
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySession
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * A library's screens belong to its session: switching to another library, straight or through
 * none, leaves nothing of them running, while a change of the app's language keeps them as they are.
 */
class LibrarySwitchTest : EnglishLocaleTest() {
    private val scope = CoroutineScope(Job())
    private var language by mutableStateOf<String?>(null)
    private val first = FakeLibrarySession()
    private var session by mutableStateOf<LibrarySession?>(first)

    /** A screen's viewmodel, which says whether it was cleared. */
    private class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    @AfterTest
    fun restore() {
        scope.cancel()
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
                language = "nl"
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
        val (before, after, probe) =
            afterVisitingADocument { window, _ ->
                checkNotNull(window.shown).apply {
                    browse.setOrder(listOf("doc-1", "doc-2"), query = "tax")
                    libraryFilter.set(LibraryFilter(query = "invoice"))
                }
                window.recreate()
                probes = window.probes
            }
        probe.cleared shouldBe false
        after.nav.routes() shouldBe listOf(LIBRARY, DOCUMENT)
        probes.distinct() shouldBe listOf(probe)
        // What the screens handed each other is still there: the detail's browse order and search,
        // and a hand-off the library has yet to take.
        after shouldNotBeSameInstanceAs before
        after.browse.neighbour("doc-1", 1) shouldBe "doc-2"
        after.browse.searchQuery shouldBe "tax"
        after.libraryFilter.consume() shouldBe LibraryFilter(query = "invoice")
    }

    /**
     * A recreated window works on the drafts it had, not on a copy built from their saved form: a
     * save still in flight settles into the drafts the new window shows, which would otherwise show
     * the values it just wrote as unsaved, and save them twice.
     */
    @Test
    fun aRecreatedWindowKeepsTheDraftsItWasSaving() {
        val (before, after, _) =
            afterVisitingADocument { window, _ ->
                checkNotNull(window.shown).drafts.field("doc-1", FieldKeys.TITLE, "Unsaved title", persisted = null)
                window.recreate()
            }
        after.drafts shouldBeSameInstanceAs before.drafts
    }

    /** A restored process has none of the session's viewmodels: its drafts come back from their saved form. */
    @Test
    fun aRestoredProcessGetsItsDraftsBackFromTheirSavedForm() {
        val (before, after, _) =
            afterVisitingADocument { window, _ ->
                checkNotNull(window.shown).drafts.field("doc-1", FieldKeys.TITLE, "Unsaved title", persisted = null)
                window.restoreProcess()
            }
        after.drafts shouldNotBeSameInstanceAs before.drafts
        after.drafts.state["doc-1"]?.fields shouldBe mapOf(FieldKeys.TITLE to "Unsaved title")
    }

    @Test
    fun anotherLibraryStartsWithoutTheOldOnesBrowseOrder() {
        val (_, after, _) =
            afterVisitingADocument { window, _ ->
                checkNotNull(window.shown).browse.setOrder(listOf("doc-1", "doc-2"))
                session = FakeLibrarySession()
                window.settle()
            }
        after.browse.positionOf("doc-1") shouldBe null
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
        private var viewModels = viewModelStoreOwner()
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

        /** As [recreate], but with none of the viewmodels: a process the system ended, then restored. */
        fun restoreProcess() {
            viewModels = viewModelStoreOwner()
            recreate()
        }

        fun close() = scene.close()

        private fun compose(): ImageComposeScene {
            val registry = registry
            return hostedScene(WIDTH, HEIGHT, LocalSaveableStateRegistry provides registry, viewModels = viewModels) {
                LibraryScreens()
            }
        }

        @Composable
        private fun LibraryScreens() {
            LocalizedApp(language, session, scope) { library ->
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
