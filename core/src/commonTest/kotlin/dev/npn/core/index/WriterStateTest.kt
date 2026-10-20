package dev.npn.core.index

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/** The single writer's transition table ([WriterState.after]), every state against every outcome. */
class WriterStateTest {
    private val damaged = MetadataWriteBlocked(WriteBlock.JOURNAL_DAMAGED, "object gone")
    private val newer = MetadataWriteBlocked(WriteBlock.NEWER_FORMAT, "unknown op")
    private val states = listOf(WriterState.Ready, WriterState.RefoldPending, WriterState.Blocked(damaged))

    @Test
    fun aPassThatReadsTheJournalWholeLeavesTheWriterReadyFromAnyState() {
        for (state in states) state.after(PassOutcome.Projected) shouldBe WriterState.Ready
    }

    @Test
    fun aFailedPassBlocksWithItsOwnRefusalFromAnyState() {
        for (state in states) state.after(PassOutcome.Failed(newer)) shouldBe WriterState.Blocked(newer)
    }

    @Test
    fun anUnfinishedPassOwesARefoldAndKeepsABlockAsItWas() {
        WriterState.Ready.after(PassOutcome.Unfinished) shouldBe WriterState.RefoldPending
        WriterState.RefoldPending.after(PassOutcome.Unfinished) shouldBe WriterState.RefoldPending
        WriterState.Blocked(damaged).after(PassOutcome.Unfinished) shouldBe WriterState.Blocked(damaged)
    }

    @Test
    fun onlyABlockRefusesWritesAndOnlyReadyOwesNoRefold() {
        states.map { it.refusal } shouldBe listOf(null, null, damaged)
        states.map { it.refoldOwed } shouldBe listOf(false, true, true)
    }

    /** Whatever a failure was, it blocks as a pass's own refusal or as a failed reconcile. */
    @Test
    fun aFailureThatIsNoRefusalBlocksAsAFailedReconcile() {
        PassOutcome.failed(damaged).refusal shouldBe damaged
        val failed = PassOutcome.failed(IllegalStateException("disk full")).refusal
        failed.block shouldBe WriteBlock.RECONCILE_FAILED
        failed.message shouldBe "disk full"
    }
}
