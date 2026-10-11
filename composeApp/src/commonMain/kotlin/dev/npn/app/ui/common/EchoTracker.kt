package dev.npn.app.ui.common

/**
 * Which values on screen are a field's own typing, still owed an echo from its owner: a persisted
 * value round-trips asynchronously, so the echo of an earlier keystroke can arrive after later
 * ones, and taking it as an edit would overwrite them. A change made elsewhere (a reset, a pick
 * from a list, a clamp) is not an echo and replaces what is on screen.
 */
internal class EchoTracker<T>(
    initial: T,
) {
    private var seen: T = initial
    private val unconfirmed = ArrayDeque<T>()

    /** Record [value] as typed by the field itself, awaiting its echo. */
    fun typed(value: T) {
        unconfirmed.addLast(value)
    }

    /**
     * What to show for the owner's [value]: null for an echo of the field's own typing (older
     * echoes are pruned with it), or [value] itself for a change made elsewhere.
     */
    fun ownerValue(value: T): T? {
        if (value == seen) return null
        seen = value
        val echo = unconfirmed.indexOf(value)
        if (echo < 0) {
            unconfirmed.clear()
            return value
        }
        repeat(echo + 1) { unconfirmed.removeFirst() }
        return null
    }
}
