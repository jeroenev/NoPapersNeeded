@file:OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.npn.app

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ImageComposeScene
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import dev.npn.app.ui.detail.DocumentDraftHost
import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.app.ui.detail.LocalDocumentDrafts
import dev.npn.app.ui.detail.has
import dev.npn.app.ui.detail.onUiThread
import dev.npn.app.ui.detail.texts
import dev.npn.core.model.FieldKeys
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

/** A change of the app's language re-renders the app in it, and loses nothing the user has not saved. */
class LanguageChangeTest {
    private var previousLocale: Locale? = null
    private val scope = CoroutineScope(Job())
    private val settings = FakeSettingsRepository()
    private val session = FakeLibrarySession()

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
    fun aLanguageChangeKeepsTheOpenLibrarysUnsavedDrafts() =
        onUiThread {
            var drafts: DocumentDrafts? = null
            val scene =
                scene {
                    LocalizedApp(settings, session, scope) { library ->
                        if (library != null) {
                            DocumentDraftHost(library.drafts) { drafts = LocalDocumentDrafts.current }
                        }
                    }
                }
            try {
                scene.settle()
                val before = checkNotNull(drafts)
                before.field("doc-1", FieldKeys.TITLE, "Unsaved title", persisted = null)

                settings.updateSettings { it.copy(appLanguage = "nl") }
                scene.settle()

                val after = checkNotNull(drafts)
                after shouldBeSameInstanceAs before
                after.state["doc-1"]?.dirty shouldBe true
            } finally {
                scene.close()
            }
        }

    /** The user was in Settings, reached from a document: after the change, that is still where they are. */
    @Test
    fun aLanguageChangeKeepsWhereTheOpenLibrarysNavigationStands() =
        onUiThread {
            var shown: LibraryUi? = null
            var subtree: Any? = null
            val scene =
                scene {
                    LocalizedApp(settings, session, scope) { library ->
                        shown = library
                        subtree = remember { Any() }
                        if (library != null) {
                            NavHost(library.nav, startDestination = LIBRARY) {
                                composable(LIBRARY) { Text(LIBRARY) }
                                composable(DOCUMENT) { Text(DOCUMENT) }
                                composable(SETTINGS) { Text(SETTINGS) }
                            }
                        }
                    }
                }
            try {
                scene.settle()
                val before = checkNotNull(shown)
                val subtreeBefore = subtree
                before.browse.setOrder(listOf("doc-1", "doc-2"))
                before.nav.navigate(DOCUMENT)
                scene.settle()
                before.nav.navigate(SETTINGS)
                scene.settle()

                settings.updateSettings { it.copy(appLanguage = "nl") }
                scene.settle()

                // The language's key did rebuild what is under it.
                subtree shouldNotBeSameInstanceAs subtreeBefore
                val after = checkNotNull(shown)
                after.nav shouldBeSameInstanceAs before.nav
                after.nav.currentBackStack.value
                    .mapNotNull { it.destination.route } shouldBe listOf(LIBRARY, DOCUMENT, SETTINGS)
                scene.has { SETTINGS in it.texts() } shouldBe true
                after.browse.neighbour("doc-1", 1) shouldBe "doc-2"
            } finally {
                scene.close()
            }
        }

    private fun scene(content: @Composable () -> Unit): ImageComposeScene {
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
            ) { content() }
        }
    }

    private fun ImageComposeScene.settle() = repeat(FRAMES) { render() }

    private companion object {
        const val WIDTH = 100
        const val HEIGHT = 100
        const val FRAMES = 3
        const val LIBRARY = "library"
        const val DOCUMENT = "document"
        const val SETTINGS = "settings"
    }
}
