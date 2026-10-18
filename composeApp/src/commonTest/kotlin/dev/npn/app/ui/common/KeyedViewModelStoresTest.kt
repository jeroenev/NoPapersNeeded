package dev.npn.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.npn.app.ui.library.DocPreviewStores
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlin.test.Test

class KeyedViewModelStoresTest {
    /** A viewmodel that says whether it was cleared. */
    private class Probe : ViewModel() {
        var cleared = false

        override fun onCleared() {
            cleared = true
        }
    }

    private fun ViewModelStoreOwner.probe(): Probe =
        ViewModelProvider.create(this, viewModelFactory { initializer { Probe() } })[Probe::class]

    @Test
    fun aKeyKeepsItsStoreUntilAnotherIsKept() {
        val stores = KeyedViewModelStores<String>()
        val first = stores.of("a").probe()
        val second = stores.of("b").probe()

        stores.of("a").probe() shouldBeSameInstanceAs first
        stores.keepOnly("b")
        first.cleared shouldBe true
        second.cleared shouldBe false
        // A key let go of starts over.
        stores.of("a").probe() shouldNotBeSameInstanceAs first

        stores.keepOnly(null)
        second.cleared shouldBe true
    }

    /** The side panel's stores go with the library screen's own, so nothing previewed outlives it. */
    @Test
    fun thePreviewStoresClearWithTheLibraryScreens() {
        val screen =
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        val previews =
            ViewModelProvider.create(screen, viewModelFactory { initializer { DocPreviewStores() } })[
                DocPreviewStores::class,
            ]
        val probe = previews.of("doc-1").probe()

        screen.viewModelStore.clear()
        probe.cleared shouldBe true
    }
}
