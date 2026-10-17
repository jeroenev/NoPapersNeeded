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

/** A [JournalEvent] of [op] on [doc], [dev]'s [seq]th, at wall time [t]: how the fold tests write events. */
fun event(
    op: JournalOp,
    doc: String?,
    seq: Long,
    t: Long,
    dev: String = "d1",
): JournalEvent = JournalEvent(JournalEventVersions.V1, seq, Hlc(t, 0, dev), dev, doc, op)
