package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeSettingsRepository
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.failure
import dev.npn.core.ai.FieldSuggestion
import dev.npn.core.ai.TagSuggestion
import dev.npn.core.app.aiedit.AiEditRun
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.ReviewDoc
import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.edit
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
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
            .batch.docs

    /**
     * A page in Review over the real run, for document a: the model suggests the library's tag
     * "invoice" and a correspondent, both recommended; [session]'s writer records the writes.
     */
    private fun TestScope.reviewingTheRealRun(session: FakeLibrarySession): AiEditViewModel {
        session.documents.documents.value = listOf(listItem("a"))
        session.tags.tagNames = listOf("invoice")
        session.aiSuggest.edit =
            edit(
                addTags = listOf(TagSuggestion("invoice", isNew = false)),
                metadata = FieldSuggestion(mapOf(FieldKeys.CORRESPONDENT to "Acme")),
            )
        val model = AiEditViewModel(session, FakeSettingsRepository(), edit = AiEditRun(session))
        model.setTask(EditTask.CUSTOM)
        model.setBackground(false)
        collecting(model.ui)
        advanceUntilIdle()
        model.start()
        advanceUntilIdle()
        return model
    }

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

    /**
     * The tag landed and the correspondent failed: Select recommended on the Retry cannot pick the
     * tag again, so the second Apply writes only the correspondent and Done counts the tag once.
     */
    @Test
    fun selectingRecommendedOnARetryNeverSendsWhatLanded() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = reviewingTheRealRun(session)
            session.meta.failApplyEditsForDocs = setOf("a")
            model.apply()
            advanceUntilIdle()
            model
                .reviewDocs()
                .single()
                .chips
                .shouldBeEmpty()

            session.meta.failApplyEditsForDocs = emptySet()
            model.selectRecommended()
            model.apply()
            advanceUntilIdle()

            session.meta.addedTags shouldContainExactly listOf(listOf("a") to "invoice")
            session.meta.batchedEdits.map { it.docId to it.fields } shouldContainExactly
                listOf("a" to mapOf(FieldKeys.CORRESPONDENT to "Acme"))
            model.ui.value.phase shouldBe EditPhase.Done(ApplyResult(applied = 2, docsChanged = 1, moreRemain = false))
        }

    /** A partly failed Apply goes back to the Review with the rows still owed (see [ReviewControllerTest]). */
    @Test
    fun aPartlyFailedApplyKeepsTheRowsStillOwed() =
        runTest(dispatcher) {
            val tagsOwed = reviewDoc("a", tags = listOf("t1"))
            val (run, model) = reviewing(tagsOwed, reviewDoc("b", fields = mapOf(FieldKeys.TITLE to "New title")))
            run.outcome = ApplyOutcome.Retry(listOf(tagsOwed), failedDocs = 1, total = 2)

            model.apply()
            advanceUntilIdle()

            model.reviewDocs() shouldContainExactly listOf(tagsOwed)
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

    /**
     * Skipping the rest of a batch after an Apply that partly landed: the tag stands, so Done says
     * so rather than that no document was changed.
     */
    @Test
    fun leavingTheReviewAfterAPartlyFailedApplyReportsWhatLanded() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = reviewingTheRealRun(session)
            session.meta.failApplyEditsForDocs = setOf("a")
            model.apply()
            advanceUntilIdle()

            model.leaveReview()
            advanceUntilIdle()

            session.meta.addedTags shouldContainExactly listOf(listOf("a") to "invoice")
            model.ui.value.phase shouldBe EditPhase.Done(ApplyResult(applied = 1, docsChanged = 1, moreRemain = false))
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
