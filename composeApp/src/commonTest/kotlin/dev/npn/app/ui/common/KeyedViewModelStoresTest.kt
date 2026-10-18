package dev.npn.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
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

    /** A screen's own store, as a back stack entry has. */
    private fun screen(): ViewModelStoreOwner =
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }

    /** The lease [screen] keeps on [stores] in its own store. */
    @Suppress("UNCHECKED_CAST") // The provider knows the erased class only; every lease here is on strings.
    private fun ViewModelStoreOwner.lease(stores: KeyedViewModelStores<String>): ViewModelStoreLease<String> =
        ViewModelProvider.create(this, viewModelFactory { initializer { ViewModelStoreLease(stores) } })[
            ViewModelStoreLease::class,
        ] as ViewModelStoreLease<String>

    @Test
    fun aKeyKeepsItsStoreUntilItsLastHolderLetsGo() {
        val stores = KeyedViewModelStores<String>()
        val first = Any()
        val second = Any()
        val probe = stores.hold("a", first).probe()

        stores.hold("a", second).probe() shouldBeSameInstanceAs probe
        stores.release("a", first)
        probe.cleared shouldBe false
        stores.release("a", second)
        probe.cleared shouldBe true
        // A key let go of starts over.
        stores.hold("a", first).probe() shouldNotBeSameInstanceAs probe
    }

    /** A screen that moves from one document to the next lets go of the one before. */
    @Test
    fun aLeaseKeepsOnlyTheKeyItIsOn() {
        val stores = KeyedViewModelStores<String>()
        val lease = screen().lease(stores)
        val a = lease.of("a").probe()
        val b = lease.of("b").probe()

        lease.keepOnly("b")
        a.cleared shouldBe true
        b.cleared shouldBe false
        lease.keepOnly(null)
        b.cleared shouldBe true
    }

    /**
     * The library's side panel and the detail screen it expands into show one document through one
     * viewmodel, which lasts until neither holds it: the detail popped, then the panel closed.
     */
    @Test
    fun thePanelAndTheDetailScreenShareADocumentsViewModel() {
        val stores = KeyedViewModelStores<String>()
        val library = screen()
        val detail = screen()
        val shown = library.lease(stores).of("doc-1").probe()

        detail.lease(stores).of("doc-1").probe() shouldBeSameInstanceAs shown
        detail.viewModelStore.clear()
        shown.cleared shouldBe false
        library.lease(stores).keepOnly(null)
        shown.cleared shouldBe true
    }

    /** A document the panel moved on from stays while a detail screen still shows it. */
    @Test
    fun aDocumentADetailScreenShowsOutlivesThePanelMovingOn() {
        val stores = KeyedViewModelStores<String>()
        val library = screen().lease(stores)
        val detail = screen()
        val shown = library.of("doc-1").probe()
        detail.lease(stores).of("doc-1")

        library.of("doc-2")
        library.keepOnly("doc-2")
        shown.cleared shouldBe false
        detail.viewModelStore.clear()
        shown.cleared shouldBe true
    }

    /** A session that ends clears every document's store, whatever its screens still held. */
    @Test
    fun clearingTheStoresClearsWhatIsStillHeld() {
        val stores = KeyedViewModelStores<String>()
        val shown = screen().lease(stores).of("doc-1").probe()

        stores.clear()
        shown.cleared shouldBe true
    }
}
