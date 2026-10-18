package dev.npn.app.ui.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.failure
import dev.npn.app.recreatable
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.ReviewDoc
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_fields_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_finish_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_apply_tags_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_more_remain_failed
import kotlin.test.Test

/** What a [BatchUnderReview] shows: the batch under review, or what an Apply came to. */
private sealed interface Shown {
    data class Review(
        val batch: ReviewBatchState,
    ) : Shown

    data class Done(
        val result: ApplyResult,
    ) : Shown
}

/**
 * The smallest [ReviewViewModel]: one batch, which Apply writes through [write], with what
 * [ReviewViewModel.applyReview] makes of the outcome in [shown]. Both AI-edit viewmodels apply
 * this way; their own tests only check that they are wired to it.
 */
private class BatchUnderReview(
    vararg docs: ReviewDoc,
    /** Where it keeps what is ticked, as a viewmodel reviewing a stored run does ([ticked]). */
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val write: suspend (List<ReviewDoc>) -> ApplyOutcome,
) : ReviewViewModel() {
    var shown: Shown =
        Shown.Review(
            ReviewBatchState(
                saved
                    .get<String>(TICKED)
                    ?.split('\n')
                    ?.toSet()
                    ?.let(docs.toList()::withTicked) ?: docs.toList(),
                emptyDocs = emptyList(),
            ),
        )

    override val reviewExit = ReviewExit.SKIP_BATCH

    override fun updateReview(transform: (List<ReviewDoc>) -> List<ReviewDoc>) {
        val review = shown as? Shown.Review ?: return
        shown = Shown.Review(review.batch.copy(docs = transform(review.batch.docs)))
        saved[TICKED] =
            (shown as Shown.Review)
                .batch.docs
                .ticked()
                .joinToString("\n")
    }

    private companion object {
        const val TICKED = "ticked"
    }

    override fun apply() {
        val review = shown as? Shown.Review ?: return
        viewModelScope.launch {
            applyReview(
                review.batch,
                Res.string.ai_edit_more_remain_failed,
                write = write,
                showReview = { shown = Shown.Review(it) },
                showDone = { shown = Shown.Done(it) },
            )
        }
    }

    override fun leaveReview() = Unit
}

/** Review → Apply as both AI-edit viewmodels share it ([ReviewViewModel.applyReview]). */
class ReviewControllerTest : MainDispatcherTest() {
    /**
     * What is ticked in a batch comes back from its saved form ([ticked], [withTicked]) over the same
     * suggestions, as a restored process ticks a stored run again: additions, removals and fields.
     */
    @Test
    fun aRestoredReviewIsTickedAsTheUserLeftIt() =
        runTest(dispatcher) {
            val docs =
                arrayOf(
                    reviewDoc("a", tags = listOf("t1"), removals = listOf("old")),
                    reviewDoc("b", fields = mapOf(FieldKeys.TITLE to "Bill")),
                )
            val screen = recreatable { BatchUnderReview(*docs, saved = it) { ApplyOutcome.NothingSelected } }
            screen.vm.toggleChip("a", "t1")
            screen.vm.toggleChip("a", "old")
            screen.vm.toggleField("b", FieldKeys.TITLE)

            val restored = screen.recreate(this, { flowOf(it.shown) })
            restored.shown
                .shouldBeInstanceOf<Shown.Review>()
                .batch.docs
                .ticked() shouldContainExactly
                listOf("a\u001F-\u001Fold")
        }

    @Test
    fun aDoneApplyShowsWhatLanded() =
        runTest(dispatcher) {
            val result = ApplyResult(applied = 2, docsChanged = 1, moreRemain = true)
            val model = BatchUnderReview(reviewDoc("a", tags = listOf("t1"))) { ApplyOutcome.Done(result) }
            val messages = collecting(model.messages.map { it.text })

            model.apply()
            advanceUntilIdle()

            model.shown shouldBe Shown.Done(result)
            messages.shouldBeEmpty()
        }

    /** The writes landed, so the batch is done even when what follows them fails; that is said. */
    @Test
    fun aDoneApplyWhoseFollowUpFailedIsStillDoneAndSaysSo() =
        runTest(dispatcher) {
            val result = ApplyResult(applied = 1, docsChanged = 1, moreRemain = false)
            val followUp = IllegalStateException("database closed")
            val model =
                BatchUnderReview(reviewDoc("a", tags = listOf("t1"))) {
                    ApplyOutcome.Done(result, followUpFailure = followUp)
                }
            val messages = collecting(model.messages.map { it.text })

            model.apply()
            advanceUntilIdle()

            model.shown shouldBe Shown.Done(result)
            messages shouldContainExactly listOf(failure(Res.string.ai_edit_more_remain_failed, "database closed"))
        }

    @Test
    fun anApplyThatFailsBeforeItsWritesLandShowsTheBatchAsItWasAndSaysSo() =
        runTest(dispatcher) {
            val model = BatchUnderReview(reviewDoc("a", tags = listOf("t1"))) { error("database closed") }
            val messages = collecting(model.messages.map { it.text })
            val before = model.shown

            model.apply()
            advanceUntilIdle()

            model.shown shouldBe before
            messages shouldContainExactly listOf(failure(Res.string.ai_edit_apply_finish_failed, "database closed"))
        }

    @Test
    fun applyingNothingShowsTheBatchAsItWas() =
        runTest(dispatcher) {
            val model = BatchUnderReview(reviewDoc("a", newTags = listOf("new1"))) { ApplyOutcome.NothingSelected }
            val messages = collecting(model.messages.map { it.text })
            val before = model.shown

            model.apply()
            advanceUntilIdle()

            model.shown shouldBe before
            messages.shouldBeEmpty()
        }

    /**
     * A partly failed Apply keeps under review only the rows still owed, whose selection names what
     * failed: the tags, the details, or both.
     */
    @Test
    fun aPartlyFailedApplyKeepsOnlyTheRowsStillOwedAndSaysWhatFailed() =
        runTest(dispatcher) {
            val tagsOwed = reviewDoc("a", tags = listOf("t1"))
            val fieldsOwed = reviewDoc("b", fields = mapOf(FieldKeys.TITLE to "New title"))
            val retries =
                ArrayDeque(
                    listOf(
                        ApplyOutcome.Retry(listOf(tagsOwed), failedDocs = 1, total = 2),
                        ApplyOutcome.Retry(listOf(fieldsOwed), failedDocs = 1, total = 2),
                        ApplyOutcome.Retry(listOf(tagsOwed, fieldsOwed), failedDocs = 2, total = 2),
                    ),
                )
            val model = BatchUnderReview(tagsOwed, fieldsOwed) { retries.removeFirst() }
            val messages = collecting(model.messages.map { it.text })

            model.apply()
            advanceUntilIdle()
            model.shown
                .shouldBeInstanceOf<Shown.Review>()
                .batch.docs shouldContainExactly listOf(tagsOwed)
            model.apply()
            advanceUntilIdle()
            model.apply()
            advanceUntilIdle()

            messages shouldContainExactly
                listOf(
                    uiText(Res.plurals.ai_edit_apply_tags_failed, 2, 1, 2),
                    uiText(Res.plurals.ai_edit_apply_fields_failed, 2, 1, 2),
                    uiText(Res.plurals.ai_edit_apply_failed, 2, 2, 2),
                )
        }
}
