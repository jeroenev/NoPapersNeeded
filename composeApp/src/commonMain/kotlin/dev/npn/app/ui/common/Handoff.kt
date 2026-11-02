package dev.npn.app.ui.common

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.mapNotNull

/**
 * A one-shot hand-off of a [T] from one screen to another it navigates to: set right before
 * navigating, consumed once by the screen it is for. Deliberately not a navigation argument, for
 * values that have no business being serialized into a route (a selection of thousands of document
 * ids, a whole filter); a hand-off lost to process death leaves the screen as it would open anyway.
 */
class Handoff<T : Any> {
    private val pending = MutableStateFlow<T?>(null)

    fun set(value: T) {
        pending.value = value
    }

    /** The pending value, if any; reading it clears it. */
    fun consume(): T? = pending.getAndUpdate { null }

    /**
     * Each value set from now on, consumed as it arrives (a pending one first): for a screen that may
     * already be showing when a value is handed to it, so no navigation brings it in afresh.
     */
    val arrivals: Flow<T> = pending.filterNotNull().mapNotNull { consume() }
}
