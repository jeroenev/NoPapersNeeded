package dev.npn.core.journal

/**
 * Folds journal events into a [FoldedState]. Because [FoldedState.apply] is commutative, the
 * result is identical regardless of event order (verified by shuffle tests).
 */
object JournalFolder {
    fun fold(events: Sequence<JournalEvent>): FoldedState {
        val state = FoldedState()
        events.forEach { state.apply(it) }
        return state
    }

    fun fold(events: Iterable<JournalEvent>): FoldedState = fold(events.asSequence())
}
