package dev.npn.app.ui.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * A [ViewModelStore] per key, shared by everything that holds the key: for viewmodels that belong to
 * one of several things (a document, which the library's side panel and the detail screen may show
 * at once) and must outlive the composition showing it. A key's store lasts while anything holds it
 * ([hold]); the last [release] clears it, so nothing started for it runs on. Screens hold keys
 * through a [ViewModelStoreLease] in their own store, so a key is held for exactly as long as a
 * screen that shows it is on the back stack, a recreated window and a change of language included.
 */
class KeyedViewModelStores<K : Any> {
    private val owners = HashMap<K, ViewModelStoreOwner>()
    private val holders = HashMap<K, MutableSet<Any>>()

    /** [key]'s store, held by [holder] until it [release]s it. */
    fun hold(
        key: K,
        holder: Any,
    ): ViewModelStoreOwner {
        holders.getOrPut(key) { HashSet() } += holder
        return owners.getOrPut(key) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }
    }

    /** Let go of [key] for [holder]; the last holder to let go clears its store. */
    fun release(
        key: K,
        holder: Any,
    ) {
        val held = holders[key] ?: return
        held -= holder
        if (held.isEmpty()) {
            holders.remove(key)
            owners.remove(key)?.viewModelStore?.clear()
        }
    }

    /** Clear every store, whoever holds it: everything that used them is gone. */
    fun clear() {
        holders.clear()
        owners.values.forEach { it.viewModelStore.clear() }
        owners.clear()
    }
}

/**
 * What one screen holds of [stores]. A viewmodel, kept in the screen's own store, so the keys it holds
 * last as long as the screen does (its back stack entry, say) and are let go of when it is cleared.
 * [of] holds a key; [keepOnly] lets go of the others, for a screen that moves from one key to the next.
 */
class ViewModelStoreLease<K : Any>(
    private val stores: KeyedViewModelStores<K>,
) : ViewModel() {
    private val held = HashSet<K>()

    fun of(key: K): ViewModelStoreOwner {
        held += key
        return stores.hold(key, this)
    }

    /** Let go of every key but [key]; null lets go of them all. */
    fun keepOnly(key: K?) {
        for (gone in held.filter { it != key }) {
            held -= gone
            stores.release(gone, this)
        }
    }

    override fun onCleared() = keepOnly(null)
}
