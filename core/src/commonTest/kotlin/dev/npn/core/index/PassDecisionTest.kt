package dev.npn.core.index

import dev.npn.core.journal.FoldedState
import dev.npn.core.journal.Hlc
import dev.npn.core.journal.JournalEvent
import dev.npn.core.journal.JournalOp
import dev.npn.core.journal.propTest
import io.kotest.matchers.shouldBe
import io.kotest.property.Exhaustive
import io.kotest.property.checkAll
import io.kotest.property.exhaustive.boolean
import io.kotest.property.exhaustive.of
import kotlin.test.Test

/** What a pass of the single writer concludes from what it read ([PassDecision]), every case. */
class PassDecisionTest {
    private val event =
        JournalEvent(
            version = 1,
            seq = 1,
            hlc = Hlc(1, 0, "device"),
            dev = "device",
            doc = "doc",
            op = JournalOp.TagAdd("tag"),
        )

    /** Only a pass that purely added onto an intact live fold extends it; one that read nothing keeps it. */
    @Test
    fun aReconcileExtendsTheLiveFoldOnlyWithPureAdditions() =
        propTest {
            checkAll(Exhaustive.boolean(), Exhaustive.boolean(), Exhaustive.of(0, 1, 3), Exhaustive.boolean()) {
                hasFold,
                cleared,
                read,
                refold,
                ->
                val step = PassDecision.foldStep(hasFold, cleared, read, refold)
                val expected =
                    when {
                        read == 0 -> FoldStep.KEEP
                        hasFold && !cleared && !refold -> FoldStep.EXTEND
                        else -> FoldStep.REFOLD
                    }
                step shouldBe expected
            }
        }

    @Test
    fun aReconcileProjectsNothingWithoutAFoldItsNewEventsWhenExtendedAndEverythingWhenRefolded() {
        val fold = FoldedState()
        PassDecision.afterReconcile(MetadataReconciliation(null, sourcesRead = 0)) shouldBe ProjectionStep.Current
        PassDecision.afterReconcile(MetadataReconciliation(fold, sourcesRead = 0)) shouldBe ProjectionStep.Unchanged
        PassDecision.afterReconcile(MetadataReconciliation(fold, sourcesRead = 2)) shouldBe ProjectionStep.Whole
        PassDecision.afterReconcile(
            MetadataReconciliation(fold, sourcesRead = 1, newEvents = listOf(event), incremental = true),
        ) shouldBe ProjectionStep.Events(listOf(event))
    }

    @Test
    fun anIngestRefoldsForARicherCopyProjectsNewEventsAndOtherwiseNothing() {
        PassDecision.afterIngest(null) shouldBe ProjectionStep.Unchanged
        PassDecision.afterIngest(MetadataAccumulator.RecordedSource(emptyList(), refold = false)) shouldBe
            ProjectionStep.Unchanged
        PassDecision.afterIngest(MetadataAccumulator.RecordedSource(listOf(event), refold = false)) shouldBe
            ProjectionStep.Events(listOf(event))
        PassDecision.afterIngest(MetadataAccumulator.RecordedSource(listOf(event), refold = true)) shouldBe
            ProjectionStep.Whole
        PassDecision.afterIngest(MetadataAccumulator.RecordedSource(emptyList(), refold = true)) shouldBe
            ProjectionStep.Whole
    }
}
