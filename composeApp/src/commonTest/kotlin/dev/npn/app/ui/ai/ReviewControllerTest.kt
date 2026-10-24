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
    /** Where it keeps what is ticked, as a viewmodel reviewing a stored run does ([SavedTicks]). */
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val write: suspend (List<ReviewDoc>) -> ApplyOutcome,
) : ReviewViewModel() {
    private val defaults = docs.toList()

    var shown: Shown =
        Shown.Review(
            ReviewBatchState(
                saved
                    .get<String>(TICKS)
                    ?.split('\n')
                    ?.let { SavedTicks.restore(defaults, it) } ?: defaults,
                emptyDocs = emptyList(),
            ),
        )

    override val reviewExit = ReviewExit.SKIP_BATCH

    override fun updateReview(transform: (List<ReviewDoc>) -> List<ReviewDoc>) {
        val review = shown as? Shown.Review ?: return
        shown = Shown.Review(review.batch.copy(docs = transform(review.batch.docs)))
        saved[TICKS] = SavedTicks.of((shown as Shown.Review).batch.docs, defaults).joinToString("\n")
    }

    private companion object {
        const val TICKS = "ticks"
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
     * What is ticked in a batch comes back from its saved form ([SavedTicks]) over the
     * same suggestions, as a restored process ticks a stored run again: additions, removals and fields,
     * ticked or unticked against the pre-select policy.
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
                .flatMap { doc ->
                    doc.chips.filter { it.selected }.map { "${doc.docId} +${it.tag}" } +
                        doc.removals.filter { it.selected }.map { "${doc.docId} -${it.tag}" } +
                        doc.fields.filter { it.selected }.map { "${doc.docId} =${it.key}" }
                } shouldContainExactly listOf("a -old")
        }

    /**
     * The saved ticks are the states the user chose, not flips of the run's own: when the run reopens
     * with other defaults (another device filled a field or removed a tag meanwhile), what the user
     * unticked stays unticked and what they ticked stays ticked, while a suggestion they left alone
     * follows the run as it is now.
     */
    @Test
    fun aRestoredReviewKeepsWhatTheUserChoseWhenTheRunsDefaultsChanged() =
        runTest(dispatcher) {
            val opened =
                listOf(
                    reviewDoc("a", tags = listOf("t1", "t2"), removals = listOf("old")),
                    reviewDoc("b", fields = mapOf(FieldKeys.TITLE to "Bill", FieldKeys.CORRESPONDENT to "Aquafin")),
                    reviewDoc("c", tags = listOf("x", "y"), newTags = listOf("n1", "n2")),
                )
            val chosen =
                opened
                    .toggleChip("a", "t1")
                    .toggleChip("a", "old")
                    .toggleField("b", FieldKeys.TITLE)
            val saved = SavedTicks.of(chosen, opened)

            // Every default the other way.
            val reopened = opened.map { it.withEverySuggestionTickedTheOtherWay() }

            SavedTicks.restore(reopened, saved).ticked() shouldContainExactly listOf("a -old", "c +n1", "c +n2")
        }

    /**
     * Whatever a run's size, its saved ticks fit the saved state, from the smallest base: deselecting
     * every suggestion saves a marker, not a list of all of them.
     */
    @Test
    fun deselectingEverySuggestionOfALargeRunSavesOnlyTheBase() =
        runTest(dispatcher) {
            val opened =
                List(LARGE_RUN) {
                    reviewDoc(
                        largeRunId(it),
                        tags = listOf("t1"),
                        fields =
                            mapOf(FieldKeys.TITLE to "Bill"),
                    )
                }
            val none = opened.setAllSelected(false)

            SavedTicks.of(none, opened) shouldContainExactly listOf("none")
            SavedTicks.restore(opened, SavedTicks.of(none, opened)).ticked().shouldBeEmpty()
            SavedTicks.of(opened.setAllSelected(true), opened) shouldContainExactly listOf("all")
        }

    /** A selection that no base saves small enough is saved, and restored, as nothing ticked. */
    @Test
    fun aSelectionTooLargeToSaveIsRestoredWithNothingTicked() =
        runTest(dispatcher) {
            val opened =
                List(LARGE_RUN) {
                    reviewDoc(
                        largeRunId(it),
                        tags = listOf("t1"),
                        fields =
                            mapOf(FieldKeys.TITLE to "Bill"),
                    )
                }
            // Half of every document ticked: as far from the run's own, from all and from none.
            val half = opened.fold(opened) { docs, doc -> docs.toggleChip(doc.docId, "t1") }

            SavedTicks.of(half, opened) shouldContainExactly listOf("none")
            SavedTicks.restore(opened, SavedTicks.of(half, opened)).ticked().shouldBeEmpty()
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

/** Each ticked suggestion of these documents, as its document, kind and what it names. */
private fun List<ReviewDoc>.ticked(): List<String> =
    flatMap { doc ->
        doc.chips.filter { it.selected }.map { "${doc.docId} +${it.tag}" } +
            doc.removals.filter { it.selected }.map { "${doc.docId} -${it.tag}" } +
            doc.fields.filter { it.selected }.map { "${doc.docId} =${it.key}" }
    }

private fun ReviewDoc.withEverySuggestionTickedTheOtherWay(): ReviewDoc =
    copy(
        chips = chips.map { it.copy(selected = !it.selected) },
        removals = removals.map { it.copy(selected = !it.selected) },
        fields = fields.map { it.copy(selected = !it.selected) },
    )

/** Documents enough that a list of every one of them does not fit the saved state. */
private const val LARGE_RUN = 600

/** A document id of a large run, as long as a real one. */
private fun largeRunId(index: Int): String = index.toString().padStart(DOC_ID_LENGTH, '0')

private const val DOC_ID_LENGTH = 64
