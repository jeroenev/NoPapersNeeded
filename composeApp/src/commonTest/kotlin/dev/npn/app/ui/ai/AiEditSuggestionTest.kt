package dev.npn.app.ui.ai

import dev.npn.app.MainDispatcherTest
import dev.npn.app.Transient
import dev.npn.app.collecting
import dev.npn.app.recreatable
import dev.npn.app.ui.common.reasonText
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.EditProgress
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_start_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_suggest_failed
import kotlin.test.Test

/** Start → Suggesting → Review: how a batch's progress and answer show on the page. */
class AiEditSuggestionTest : MainDispatcherTest() {
    /**
     * A batch being suggested is work in flight: it dies with the process, so a restored page is
     * back on the setup form ([Transient.aiEdit]), as configured, with nothing of the batch spent.
     */
    @Test
    fun aRestoredProcessIsBackOnTheSetupFormWhenABatchWasBeingSuggested() =
        runTest(dispatcher) {
            val screen =
                recreatable { saved ->
                    vm(
                        FakeAiEditRun().apply {
                            inScope("a", "b")
                            gate = CompletableDeferred()
                            progress =
                                listOf(
                                    EditProgress(done = 0, total = 2, currentTitle = null, currentFileName = "a.pdf"),
                                )
                            batch = review(reviewDoc("a", tags = listOf("t")))
                        },
                        saved = saved,
                    )
                }
            collecting(screen.vm.ui)
            advanceUntilIdle()
            screen.vm.start()
            advanceUntilIdle()
            screen.vm.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Suggesting>()

            val restored =
                screen.recreate(this, { combine(it.ui, it.preview, ::Pair) }) { (ui, preview) ->
                    Transient.aiEdit(ui) to
                        preview
                }
            restored.ui.value.phase shouldBe EditPhase.Idle
            restored.preview.value.total shouldBe 2
        }

    @Test
    fun theRunsProgressIsTheSuggestingPhase() =
        runTest(dispatcher) {
            val run =
                FakeAiEditRun().apply {
                    inScope("a", "b")
                    progress = listOf(progress(0, "a"), progress(1, "a"))
                    gate = CompletableDeferred()
                    batch = review(reviewDoc("a", tags = listOf("t")), reviewDoc("b", tags = listOf("t")))
                }
            val model = vm(run)
            val shown = collecting(model.ui.map { (it.phase as? EditPhase.Suggesting)?.progress?.done })
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()
            model.ui.value.phase shouldBe EditPhase.Suggesting(progress(1, "a"))

            run.gate?.complete(Unit)
            advanceUntilIdle()

            shown.filterNotNull() shouldContainExactly listOf(0, 1)
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()
                .batch.docs
                .map { it.docId } shouldContainExactly listOf("a", "b")
        }

    @Test
    fun documentsWithoutSuggestionsAreShownApart() =
        runTest(dispatcher) {
            val run =
                FakeAiEditRun().apply {
                    inScope("a", "b")
                    batch = review(reviewDoc("a", tags = listOf("t")), empty = listOf(reviewDoc("b")))
                }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            val review =
                model.ui.value.phase
                    .shouldBeInstanceOf<EditPhase.Review>()
            review.batch.docs.map { it.docId } shouldContainExactly listOf("a")
            review.batch.emptyDocs.map { it.docId } shouldContainExactly listOf("b")
        }

    @Test
    fun aBatchWithNothingLeftEndsTheRun() =
        runTest(dispatcher) {
            // The scope still lists a document, but the run has nothing left to ask about.
            val run = FakeAiEditRun().apply { inScope("a") }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            model.ui.value.phase shouldBe EditPhase.Done(ApplyResult(0, 0, moreRemain = false))
        }

    @Test
    fun documentsTheModelFailedOnAreReportedInOneMessage() =
        runTest(dispatcher) {
            val run =
                FakeAiEditRun().apply {
                    inScope("a", "b")
                    batch = review(reviewDoc("b", tags = listOf("t")), failures = 1)
                }
            val model = vm(run)
            collecting(model.ui)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.plurals.ai_edit_suggest_failed, 2, 1, 2))
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()
                .batch.docs
                .map { it.docId } shouldContainExactly listOf("b")
        }

    @Test
    fun cancellingMidRunReturnsToIdle() =
        runTest(dispatcher) {
            val run =
                FakeAiEditRun().apply {
                    inScope("a")
                    progress = listOf(progress(0, "a"))
                    gate = CompletableDeferred()
                    batch = review(reviewDoc("a", tags = listOf("t")))
                }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()
            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Suggesting>()
            model.cancel()
            run.gate?.complete(Unit)
            advanceUntilIdle()

            // The batch's answer arrives after the cancel and must not bring the page back.
            model.ui.value.phase shouldBe EditPhase.Idle
        }

    @Test
    fun aStartWhoseScopeCannotBeReadIsReported() =
        runTest(dispatcher) {
            val failure = IllegalStateException("database closed")
            val run =
                FakeAiEditRun().apply {
                    inScope("a")
                    remainingFailure = failure
                }
            val model = vm(run)
            collecting(model.ui)
            val messages = collecting(model.messages.map { it.text })
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            messages shouldContainExactly listOf(uiText(Res.string.ai_edit_start_failed, failure.reasonText()))
            model.ui.value.phase shouldBe EditPhase.Idle
        }

    @Test
    fun aCancelAfterTheBatchLandedKeepsTheReview() =
        runTest(dispatcher) {
            val run =
                FakeAiEditRun().apply {
                    inScope("a")
                    progress = listOf(progress(0, "a"))
                    gate = CompletableDeferred()
                    batch = review(reviewDoc("a", tags = listOf("t")))
                }
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()
            run.gate?.complete(Unit)
            advanceUntilIdle()
            // A Cancel still on screen from the last frame lands after the Review did.
            model.cancel()

            model.ui.value.phase
                .shouldBeInstanceOf<EditPhase.Review>()
                .batch.docs
                .map { it.docId } shouldContainExactly listOf("a")
        }

    private fun progress(
        done: Int,
        docId: String,
    ) = EditProgress(done = done, total = 2, currentTitle = null, currentFileName = "$docId.pdf")
}
