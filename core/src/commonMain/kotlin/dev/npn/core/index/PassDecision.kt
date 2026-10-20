package dev.npn.core.index

import dev.npn.core.journal.JournalEvent

// What a pass of the single writer concludes from what it read of the journal, as pure functions:
// how the fold takes the sources a reconcile read ([PassDecision.foldStep]) and what the projection
// then needs ([PassDecision.afterReconcile], [PassDecision.afterIngest]). How the pass ends for the
// writer's state is [WriterState.after]'s.

/** How a reconcile pass brings the live fold in step with the sources it read. */
internal enum class FoldStep {
    /** It read nothing: the fold it had, none included, is the store's. */
    KEEP,

    /** It only added events onto an intact live fold: apply those to it. */
    EXTEND,

    /** Anything else: fold the store again in full. */
    REFOLD,
}

/** What the projection needs once a pass has brought the live fold in step. */
internal sealed interface ProjectionStep {
    /** Nothing on disk changed and the session never needed the fold: nothing to fold or project. */
    data object Current : ProjectionStep

    /** The fold the projection was built from is unchanged: nothing to project. */
    data object Unchanged : ProjectionStep

    /** The fold only gained [events]: project the documents they touch. */
    data class Events(
        val events: List<JournalEvent>,
    ) : ProjectionStep

    /** The fold was rebuilt: reproject every document. */
    data object Whole : ProjectionStep
}

internal object PassDecision {
    /**
     * How a reconcile that read [sourcesRead] sources takes them, onto a live fold or none
     * ([hasFold]). Only a pass that purely added events onto an intact fold extends it: a wiped
     * projection ([projectionCleared], whose tables went with it), a re-ingested (mutated) source or
     * a richer copy of a stored event ([refold]), or no fold to extend all fold again in full. A gone
     * source changes no event: the pass fails, or another source holds all of its.
     */
    fun foldStep(
        hasFold: Boolean,
        projectionCleared: Boolean,
        sourcesRead: Int,
        refold: Boolean,
    ): FoldStep =
        when {
            sourcesRead == 0 -> FoldStep.KEEP
            hasFold && !projectionCleared && !refold -> FoldStep.EXTEND
            else -> FoldStep.REFOLD
        }

    /** What the projection needs after a reconcile pass that ended in [reconciliation]. */
    fun afterReconcile(reconciliation: MetadataReconciliation): ProjectionStep =
        when {
            reconciliation.state == null -> ProjectionStep.Current
            reconciliation.incremental -> ProjectionStep.Events(reconciliation.newEvents)
            reconciliation.sourcesRead > 0 -> ProjectionStep.Whole
            else -> ProjectionStep.Unchanged
        }

    /**
     * What the projection needs after the watch ingested one object, which [recorded] (null when it
     * was gone, or applied unchanged already): a richer copy of an event the live fold holds only a
     * full refold takes in; new events are projected; none leave it as it was.
     */
    fun afterIngest(recorded: MetadataAccumulator.RecordedSource?): ProjectionStep =
        when {
            recorded == null -> ProjectionStep.Unchanged
            recorded.refold -> ProjectionStep.Whole
            recorded.events.isEmpty() -> ProjectionStep.Unchanged
            else -> ProjectionStep.Events(recorded.events)
        }
}
