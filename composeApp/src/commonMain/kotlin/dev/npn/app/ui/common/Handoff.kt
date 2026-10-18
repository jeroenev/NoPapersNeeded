package dev.npn.app.ui.common

/**
 * A one-shot hand-off of a [T] from one screen to another it navigates to: set right before
 * navigating, consumed once by the screen it is for. Deliberately not a navigation argument, for
 * values that have no business being serialized into a route (a selection of thousands of document
 * ids, a whole filter); a hand-off lost to process death leaves the screen as it would open anyway.
 */
class Handoff<T : Any> {
    private var pending: T? = null

    fun set(value: T) {
        pending = value
    }

    /** The pending value, if any; reading it clears it. */
    fun consume(): T? = pending.also { pending = null }
}
