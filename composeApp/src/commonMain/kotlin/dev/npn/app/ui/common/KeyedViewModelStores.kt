package dev.npn.app.ui.common

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner

/**
 * A [ViewModelStore] per key, for viewmodels that belong to one of several things (a library
 * session, a previewed document) and must outlive the composition showing it. Held by a retained
 * owner, so a recreated window or a change of language finds them as they were; [keepOnly] clears
 * every store but the one still wanted, so nothing started for the others runs on.
 */
class KeyedViewModelStores<K : Any> {
    private val owners = HashMap<K, ViewModelStoreOwner>()

    fun of(key: K): ViewModelStoreOwner =
        owners.getOrPut(key) {
            object : ViewModelStoreOwner {
                override val viewModelStore = ViewModelStore()
            }
        }

    /** Clear every store but [key]'s; null clears them all. */
    fun keepOnly(key: K?) {
        for (gone in owners.keys.filter { it != key }) owners.remove(gone)?.viewModelStore?.clear()
    }
}
