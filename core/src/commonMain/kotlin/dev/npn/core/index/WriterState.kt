package dev.npn.core.index

import dev.npn.core.util.reason

/**
 * Where the session's single writer ([JournalCoordinator]) stands between passes: whether the
 * projection is the fold of the journal it last read, and whether writes are taken.
 *
 * | State | Writes | Next reconcile or ingest |
 * |---|---|---|
 * | [Ready] | taken | builds on the live fold, incrementally |
 * | [RefoldPending] | taken | refolds and reprojects in full |
 * | [Blocked] | refused with [Blocked.refusal] | refolds and reprojects in full |
 *
 * Every pass ends in one [PassOutcome], and [after] is the whole transition table. The live fold is
 * a cache beside the state: the coordinator drops it on every outcome but [PassOutcome.Projected],
 * since a pass that stopped part way may have recorded a source it never folded or projected.
 */
internal sealed interface WriterState {
    /** What refuses writes in this state; null while they are taken. */
    val refusal: MetadataWriteBlocked?

    /**
     * Whether the projection may lack sources an earlier pass recorded, so the next reconcile or
     * ingest refolds and reprojects in full rather than build on what it has.
     */
    val refoldOwed: Boolean

    /** This state once a pass ended in [outcome]. */
    fun after(outcome: PassOutcome): WriterState =
        when (outcome) {
            PassOutcome.Projected -> Ready

            // Says nothing about the journal: a block stands, and nothing new blocks.
            PassOutcome.Unfinished -> if (this is Blocked) this else RefoldPending

            is PassOutcome.Failed -> Blocked(outcome.refusal)
        }

    /** The projection is the fold of the journal as the last pass read it, and writes are taken. */
    data object Ready : WriterState {
        override val refusal: MetadataWriteBlocked? get() = null
        override val refoldOwed: Boolean get() = false
    }

    /** A pass was cut short: writes are taken, but the projection owes a full refold. */
    data object RefoldPending : WriterState {
        override val refusal: MetadataWriteBlocked? get() = null
        override val refoldOwed: Boolean get() = true
    }

    /**
     * A pass failed: writes are refused with [refusal] until a pass reads the journal whole, which
     * refolds and reprojects in full.
     */
    data class Blocked(
        override val refusal: MetadataWriteBlocked,
    ) : WriterState {
        override val refoldOwed: Boolean get() = true
    }
}

/** How one pass of the single writer ended, as [WriterState.after] takes it. */
internal sealed interface PassOutcome {
    /** The pass read the journal whole and the projection is its fold (or was already). */
    data object Projected : PassOutcome

    /**
     * The pass stopped short of projecting all it recorded: it was cancelled part way, or it found a
     * change only a full refold takes in (a richer copy of an event the live fold holds).
     */
    data object Unfinished : PassOutcome

    /** The pass failed, refusing writes with [refusal]. */
    data class Failed(
        val refusal: MetadataWriteBlocked,
    ) : PassOutcome

    companion object {
        /** The outcome of a pass that threw [failure]: its own refusal keeps its reason, any other is the pass's. */
        fun failed(failure: Throwable): Failed =
            Failed(
                failure as? MetadataWriteBlocked ?: MetadataWriteBlocked(WriteBlock.RECONCILE_FAILED, failure.reason()),
            )
    }
}
