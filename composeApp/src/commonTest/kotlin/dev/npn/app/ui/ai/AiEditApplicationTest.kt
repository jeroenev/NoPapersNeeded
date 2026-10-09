@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.app.ui.ai

import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.failure
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.ApplyOutcome
import dev.npn.core.app.ApplyResult
import dev.npn.core.app.ReviewDoc
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_fields_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_finish_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_tags_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_more_remain_failed
import kotlin.test.Test

/** Review → Apply: selecting in the review, and how the run's apply outcome shows on the page. */
class AiEditApplicationTest : MainDispatcherTest() {
    /** A page in Review over [rows], which the run answered for a scope of the same documents. */
    private fun TestScope.reviewing(vararg rows: ReviewDoc): Pair<FakeAiEditRun, AiEditViewModel> {
        val run =
            FakeAiEditRun().apply {
                inScope(*rows.map { it.docId }.toTypedArray())
                batch = review(*rows)
            }
        val model = vm(run)
        collecting(model.ui)
        advanceUntilIdle()
        model.start()
        advanceUntilIdle()
        return run to model
    }

    private fun AiEditViewModel.reviewDocs() =
        ui.value.phase
            .shouldBeInstanceOf<EditPhase.Review>()
            .docs

    @Test
    fun toggleChipFlipsAddAndRemovalSelection() =
        runTest(dispatcher) {
            val (_, model) = reviewing(reviewDoc("a", tags = listOf("invoice"), removals = listOf("utility")))

            model.toggleChip("a", "invoice")
            model.toggleChip("a", "utility")

            val doc = model.reviewDocs().single()
            doc.chips.single().selected shouldBe false
            doc.removals.single().selected shouldBe true
        }

    @Test
    fun toggleFieldFlipsSelection() =
        runTest(dispatcher) {
            val (_, model) = reviewing(reviewDoc("a", fields = mapOf(FieldKeys.TITLE to "New title")))

            model.toggleField("a", FieldKeys.TITLE)

            model
                .reviewDocs()
                .single()
                .fields
                .single()
                .selected shouldBe false
        }

    @Test
    fun setAllAndSetDocSelectionToggleAllKinds() =
        runTest(dispatcher) {
            val (_, model) =
                reviewing(
                    reviewDoc(
                        "a",
                        tags = listOf("t1"),
                        removals = listOf("utility"),
                        fields = mapOf(FieldKeys.TITLE to "New title"),
                        current = mapOf(FieldKeys.TITLE to "Old title"),
                    ),
                    reviewDoc("b", tags = listOf("t1")),
                )

            // Overwrites and removals start deselected; select-all must pick them up too.
            model.setAllSelection(true)
            model
                .reviewDocs()
                .single { it.docId == "a" }
                .let { (it.chips + it.removals).all { s -> s.selected } && it.fields.all { s -> s.selected } }
                .shouldBe(true)

            model.setDocSelection("a", false)
            val docs = model.reviewDocs()
            docs
                .single { it.docId == "a" }
                .let { (it.chips + it.removals).none { s -> s.selected } && it.fields.none { s -> s.selected } }
                .shouldBe(true)
            docs
                .single { it.docId == "b" }
                .chips
                .single()
                .selected shouldBe true
        }

    @Test
    fun applyHandsTheRunTheRowsAsSelectedAndShowsItsCounts() =
        runTest(dispatcher) {
            val (run, model) = reviewing(reviewDoc("a", tags = listOf("t1"), newTags = listOf("new1")))
            run.outcome = ApplyOutcome.Done(ApplyResult(applied = 2, docsChanged = 1, moreRemain = true))

            // An invented tag starts deselected; the user takes it as well.
            model.toggleChip("a", "new1")
            model.apply()
            advanceUntilIdle()

            run.applied
                .single()
                .single()
                .chips
                .all { it.selected } shouldBe true
            model.ui.value.phase shouldBe EditPhase.Done(ApplyResult(applied = 2, docsChanged = 1, moreRemain = true))
        }

    @Test
    fun applyingNothingStaysInTheReview() =
        runTest(dispatcher) {
            val (run, model) = reviewing(reviewDoc("a", newTags = listOf("new1")))
            run.outcome = ApplyOutcome.NothingSelected

            model.apply()
            advanceUntilIdle()

            model.reviewDocs().map { it.docId } shouldContainExactly listOf("a")
        }

    @Test
    fun aPartlyFailedApplyReportsWhatItCouldNotWriteAndKeepsThoseRows() =
        runTest(dispatcher) {
            val tagsOwed = reviewDoc("a", tags = listOf("t1"))
            val fieldsOwed = reviewDoc("b", fields = mapOf(FieldKeys.TITLE to "New title"))
            val (run, model) = reviewing(tagsOwed, fieldsOwed)
            val messages = collecting(model.messages.map { it.text })

            // The retry's rows keep selected exactly what is still owed, which names the message.
            run.outcome = ApplyOutcome.Retry(listOf(tagsOwed), failedDocs = 1, total = 2)
            model.apply()
            advanceUntilIdle()
            model.reviewDocs().map { it.docId } shouldContainExactly listOf("a")

            run.outcome = ApplyOutcome.Retry(listOf(fieldsOwed), failedDocs = 1, total = 2)
            model.apply()
            advanceUntilIdle()

            run.outcome = ApplyOutcome.Retry(listOf(tagsOwed, fieldsOwed), failedDocs = 2, total = 2)
            model.apply()
            advanceUntilIdle()

            messages shouldContainExactly
                listOf(
                    uiText(Res.plurals.ai_edit_apply_tags_failed, 2, 1, 2),
                    uiText(Res.plurals.ai_edit_apply_fields_failed, 2, 1, 2),
                    uiText(Res.plurals.ai_edit_apply_failed, 2, 2, 2),
                )
        }

    /**
     * Once the writes landed the batch is applied, even when asking whether more documents remain
     * then fails: the page shows Done with what was written and no next batch, says why, and there
     * is no second Apply to write it all again.
     */
    @Test
    fun anApplyWhoseNextBatchCheckFailsIsDoneWithoutANextBatchAndSaysSo() =
        runTest(dispatcher) {
            val (run, model) = reviewing(reviewDoc("a", tags = listOf("t1")))
            val messages = collecting(model.messages.map { it.text })
            val result = ApplyResult(applied = 1, docsChanged = 1, moreRemain = false)
            run.outcome = ApplyOutcome.Done(result, followUpFailure = IllegalStateException("database closed"))

            model.apply()
            advanceUntilIdle()
            model.apply()
            advanceUntilIdle()

            model.ui.value.phase shouldBe EditPhase.Done(result)
            messages shouldContainExactly listOf(failure(Res.string.ai_edit_more_remain_failed, "database closed"))
            run.applied.size shouldBe 1
        }

    /** An Apply that fails before its writes land goes back to the Review as it was, and says so. */
    @Test
    fun anApplyThatFailsBeforeItsWritesLandGoesBackToTheReviewAndSaysSo() =
        runTest(dispatcher) {
            val (run, model) = reviewing(reviewDoc("a", tags = listOf("t1")))
            val messages = collecting(model.messages.map { it.text })
            val review = model.ui.value.phase
            run.applyFailure = IllegalStateException("database closed")

            model.apply()
            advanceUntilIdle()

            model.ui.value.phase shouldBe review
            messages shouldContainExactly listOf(failure(Res.string.ai_edit_apply_finish_failed, "database closed"))

            run.applyFailure = null
            run.outcome = ApplyOutcome.Done(ApplyResult(applied = 1, docsChanged = 1, moreRemain = false))
            model.apply()
            advanceUntilIdle()
            model.ui.value.phase shouldBe EditPhase.Done(ApplyResult(applied = 1, docsChanged = 1, moreRemain = false))
        }

    @Test
    fun leavingTheReviewWritesNothingAndSaysWhetherMoreRemain() =
        runTest(dispatcher) {
            val (run, model) = reviewing(reviewDoc("a", newTags = listOf("brandnew")))
            run.moreLeft = true

            model.leaveReview()
            advanceUntilIdle()

            run.applied.shouldBeEmpty()
            model.ui.value.phase shouldBe EditPhase.Done(ApplyResult(0, 0, moreRemain = true))
        }

    /** Leaving the review when asking whether more remain fails ends in Done with no next batch, and says why. */
    @Test
    fun leavingTheReviewWhenTheNextBatchCheckFailsIsDoneAndSaysSo() =
        runTest(dispatcher) {
            val (run, model) = reviewing(reviewDoc("a", newTags = listOf("brandnew")))
            val messages = collecting(model.messages.map { it.text })
            run.moreLeft = true
            run.moreRemainFailure = IllegalStateException("database closed")

            model.leaveReview()
            advanceUntilIdle()

            model.ui.value.phase shouldBe EditPhase.Done(ApplyResult(0, 0, moreRemain = false))
            messages shouldContainExactly listOf(failure(Res.string.ai_edit_more_remain_failed, "database closed"))
        }
}
