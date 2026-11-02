package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.failure
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.aiedit.ApplyOutcome
import dev.npn.core.app.aiedit.ApplyResult
import dev.npn.core.app.aiedit.ReviewDoc
import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.BatchedEdit
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_applied
import no_papers_needed.composeapp.generated.resources.ai_edit_undo_failed
import no_papers_needed.composeapp.generated.resources.ai_edit_undone
import no_papers_needed.composeapp.generated.resources.library_undo
import kotlin.test.Test

/** The Undo an Apply offers: it puts back exactly what the Apply wrote, and only that. */
class AiEditUndoTest : MainDispatcherTest() {
    private val session = FakeLibrarySession()

    /** A page in Review over [rows]; the run answers [outcomes] to the Applies in turn. */
    private fun TestScope.reviewing(
        vararg rows: ReviewDoc,
        outcomes: List<ApplyOutcome>,
    ): AiEditViewModel {
        val run =
            FakeAiEditRun().apply {
                inScope(*rows.map { it.docId }.toTypedArray())
                batch = review(*rows)
                this.outcomes += outcomes
            }
        val model = vm(run, session)
        collecting(model.ui)
        advanceUntilIdle()
        model.start()
        advanceUntilIdle()
        return model
    }

    private fun List<UiMessage>.undo(): () -> Unit = last { it.action != null }.action.shouldNotBeNull().onPerform

    @Test
    fun anApplyOffersAnUndoThatPutsBackWhatItWrote() =
        runTest(dispatcher) {
            session.documents.documents.value =
                listOf(listItem(doc("a", correspondent = "Old Co"), tags = listOf("utility")))
            val model =
                reviewing(
                    reviewDoc(
                        "a",
                        tags = listOf("invoice"),
                        removals = listOf("utility"),
                        fields = mapOf(FieldKeys.CORRESPONDENT to "Acme"),
                        current = mapOf(FieldKeys.CORRESPONDENT to "Old Co"),
                    ),
                    outcomes = listOf(ApplyOutcome.Done(ApplyResult(3, 1, moreRemain = false))),
                )
            val messages = collecting(model.messages)
            model.setAllSelection(true)

            model.apply()
            advanceUntilIdle()

            val offer = messages.last()
            offer.text shouldBe uiText(Res.plurals.ai_edit_applied, 1, 1)
            offer.action?.label shouldBe uiText(Res.string.library_undo)

            messages.undo()()
            advanceUntilIdle()

            session.meta.removedTags shouldContainExactly listOf(listOf("a") to "invoice")
            session.meta.addedTags shouldContainExactly listOf(listOf("a") to "utility")
            session.meta.batchedEdits shouldContainExactly
                listOf(BatchedEdit(listOf("a"), emptyList(), mapOf(FieldKeys.CORRESPONDENT to "Old Co")))
            messages.last().text shouldBe uiText(Res.plurals.ai_edit_undone, 1, 1)
        }

    /**
     * The values put back are the document's just before the Apply, not the ones the Review was
     * projected against: an edit made in between stands, and a field that was empty is cleared.
     */
    @Test
    fun theUndoPutsBackTheValuesTheDocumentHadJustBeforeTheApply() =
        runTest(dispatcher) {
            session.documents.documents.value = listOf(listItem(doc("a", correspondent = "Edited meanwhile")))
            val model =
                reviewing(
                    reviewDoc(
                        "a",
                        fields = mapOf(FieldKeys.CORRESPONDENT to "Acme", FieldKeys.TITLE to "Bill"),
                        current = mapOf(FieldKeys.CORRESPONDENT to "Projected"),
                    ),
                    outcomes = listOf(ApplyOutcome.Done(ApplyResult(2, 1, moreRemain = false))),
                )
            val messages = collecting(model.messages)
            model.setAllSelection(true)
            model.apply()
            advanceUntilIdle()

            messages.undo()()
            advanceUntilIdle()

            session.meta.batchedEdits
                .single()
                .fields shouldBe
                mapOf(FieldKeys.CORRESPONDENT to "Edited meanwhile", FieldKeys.TITLE to null)
            session.meta.addedTags.shouldBeEmpty()
            session.meta.removedTags.shouldBeEmpty()
        }

    /** A batch that took a Retry to land is undone whole: what the first attempt wrote goes back too. */
    @Test
    fun anUndoAfterARetryPutsBackEveryAttempt() =
        runTest(dispatcher) {
            val owed = reviewDoc("b", fields = mapOf(FieldKeys.TITLE to "New title"))
            session.documents.documents.value = listOf(listItem("a"), listItem("b"))
            val model =
                reviewing(
                    reviewDoc("a", tags = listOf("t1")),
                    owed,
                    outcomes =
                        listOf(
                            ApplyOutcome.Retry(listOf(owed), failedDocs = 1, total = 2),
                            ApplyOutcome.Done(ApplyResult(2, 2, moreRemain = false)),
                        ),
                )
            val messages = collecting(model.messages)
            model.apply()
            advanceUntilIdle()
            messages.none { it.action != null } shouldBe true

            model.apply()
            advanceUntilIdle()
            messages.last().text shouldBe uiText(Res.plurals.ai_edit_applied, 2, 2)
            messages.undo()()
            advanceUntilIdle()

            session.meta.removedTags shouldContainExactly listOf(listOf("a") to "t1")
            session.meta.batchedEdits.map { it.docId to it.fields } shouldContainExactlyInAnyOrder
                listOf("b" to mapOf(FieldKeys.TITLE to null))
        }

    /** A part of the Undo that fails is said, with why; the rest still goes back. */
    @Test
    fun aFailedUndoSaysWhyAndPutsBackTheRest() =
        runTest(dispatcher) {
            session.documents.documents.value = listOf(listItem("a"))
            val model =
                reviewing(
                    reviewDoc("a", tags = listOf("t1"), fields = mapOf(FieldKeys.TITLE to "Bill")),
                    outcomes = listOf(ApplyOutcome.Done(ApplyResult(2, 1, moreRemain = false))),
                )
            val messages = collecting(model.messages)
            model.apply()
            advanceUntilIdle()
            session.meta.failRemoveTagForDocs = setOf("a")

            messages.undo()()
            advanceUntilIdle()

            messages.map { it.text } shouldContain failure(Res.string.ai_edit_undo_failed, "removeTag failed for [a]")
            session.meta.batchedEdits.map { it.docId } shouldContainExactly listOf("a")
        }
}
