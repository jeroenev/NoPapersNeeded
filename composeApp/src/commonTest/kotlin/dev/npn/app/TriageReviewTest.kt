package dev.npn.app

import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.app.ui.detail.commitTriageReview
import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.FakeMetadataWriter
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The triage mode's half of the editor's save model: "mark reviewed and next" is one save of the
 * draft and the marker followed by the move, Undo takes all of it back (the marker and the tags
 * too), and leaving a document (an arrow-key move) saves its draft.
 */
class TriageReviewTest : MainDispatcherTest() {
    private val stored = mapOf(FieldKeys.TITLE to "Old")

    @Test
    fun markingReviewedWritesTheDraftAndTheMarkerThenMovesOn() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val drafts = DocumentDrafts(writer, this)
            drafts.registerEditing("one")
            drafts.field("one", FieldKeys.TITLE, "New", persisted = "Old")
            var moved = 0

            drafts.commitTriageReview("one", stored, "New", wasReviewed = false) { moved++ }
            advanceUntilIdle()

            writer.batchedEdits.single().fields shouldBe mapOf(FieldKeys.TITLE to "New")
            writer.reviewedSets shouldContainExactly listOf(listOf("one") to true)
            moved shouldBe 1
        }

    @Test
    fun movingOnSavesTheDraftOfTheDocumentLeft() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val drafts = DocumentDrafts(writer, this)
            drafts.registerEditing("one")
            drafts.field("one", FieldKeys.TITLE, "New", persisted = "Old")

            // A move disposes the old document's editor before the next one registers.
            drafts.unregisterEditing("one")
            drafts.left("one", stored, "New")
            advanceUntilIdle()

            writer.batchedEdits.single().fields shouldBe mapOf(FieldKeys.TITLE to "New")
        }

    /** A save that fails keeps the draft and does not run its move: the user stays on the document. */
    @Test
    fun aFailedReviewWriteDoesNotMoveOn() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter().apply { failBatchOps = { error("write failed") } }
            val drafts = DocumentDrafts(writer, this)
            drafts.registerEditing("one")
            drafts.field("one", FieldKeys.TITLE, "New", persisted = "Old")
            var moved = 0

            drafts.commitTriageReview("one", stored, "New", wasReviewed = false) { moved++ }
            advanceUntilIdle()

            moved shouldBe 0
            drafts.state.getValue("one").error shouldBe "write failed"
        }

    /** The most common triage action, marking reviewed with nothing edited, can be taken back too. */
    @Test
    fun undoAfterMarkingReviewedWithNoEditsUnmarksIt() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val drafts = DocumentDrafts(writer, this)
            drafts.registerEditing("one")

            drafts.commitTriageReview("one", stored, "Old", wasReviewed = false) {}
            runCurrent()
            drafts.undo("one")
            runCurrent()

            writer.reviewedSets shouldContainExactly listOf(listOf("one") to true, listOf("one") to false)
            writer.batchedEdits.shouldBeEmpty()
        }

    /**
     * "Mark reviewed & next" leaves the document at once, so its Undo is the notice that outlives
     * it, and it puts back the fields and the document's place in the Inbox.
     */
    @Test
    fun undoAfterMarkingReviewedAndMovingOnRestoresTheFieldsAndUnmarksIt() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val drafts = DocumentDrafts(writer, this)
            val notices = collecting(drafts.notices.messages)
            drafts.registerEditing("one")
            drafts.field("one", FieldKeys.TITLE, "New", persisted = "Old")

            drafts.commitTriageReview("one", stored, "New", wasReviewed = false) {
                // The move: the document's editor goes away.
                drafts.unregisterEditing("one")
                drafts.left("one", stored, "New")
            }
            runCurrent()
            notices
                .single()
                .action
                .shouldNotBeNull()
                .onPerform()
            runCurrent()

            writer.batchedEdits.last().fields shouldBe mapOf(FieldKeys.TITLE to "Old")
            writer.reviewedSets shouldContainExactly listOf(listOf("one") to true, listOf("one") to false)
        }

    /** Marking an already reviewed document writes no marker change, so there is nothing to unmark. */
    @Test
    fun undoLeavesAMarkerThatWasAlreadySetAlone() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val drafts = DocumentDrafts(writer, this)
            drafts.registerEditing("one")
            drafts.field("one", FieldKeys.TITLE, "New", persisted = "Old")

            drafts.commitTriageReview("one", stored, "New", wasReviewed = true) {}
            runCurrent()
            drafts.undo("one")
            runCurrent()

            writer.batchedEdits.last().fields shouldBe mapOf(FieldKeys.TITLE to "Old")
            writer.reviewedSets shouldContainExactly listOf(listOf("one") to true)
        }

    /** Tags change at once, not through the draft, yet the same Undo takes them back with the rest. */
    @Test
    fun undoTakesBackTheTagsChangedOnTheDocument() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val drafts = DocumentDrafts(writer, this)
            drafts.registerEditing("one")

            drafts.tagged("one", "Old", "invoice", added = true)
            drafts.tagged("one", "Old", "draft", added = false)
            drafts.commitTriageReview("one", stored, "Old", wasReviewed = false) {}
            runCurrent()
            drafts.undo("one")
            runCurrent()

            writer.removedTags shouldContainExactly listOf(listOf("one") to "invoice")
            writer.addedTags shouldContainExactly listOf(listOf("one") to "draft")
            writer.reviewedSets.last() shouldBe (listOf("one") to false)
        }

    /** A tag added and then removed again before Undo nets out: Undo writes no tag at all. */
    @Test
    fun aTagAddedAndRemovedAgainLeavesNoTagToUndo() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val drafts = DocumentDrafts(writer, this)
            drafts.registerEditing("one")

            drafts.tagged("one", "Old", "invoice", added = true)
            drafts.tagged("one", "Old", "invoice", added = false)
            drafts.undo("one")
            runCurrent()

            writer.addedTags.shouldBeEmpty()
            writer.removedTags.shouldBeEmpty()
        }

    /** A tag change on a document no longer on screen offers its Undo as a notice. */
    @Test
    fun aTagChangeOffScreenOffersUndoAsANotice() =
        runTest(dispatcher) {
            val writer = FakeMetadataWriter()
            val drafts = DocumentDrafts(writer, this)
            val notices = collecting(drafts.notices.messages)

            drafts.tagged("one", "Old", "invoice", added = true)
            runCurrent()
            notices
                .single()
                .action
                .shouldNotBeNull()
                .onPerform()
            runCurrent()

            writer.removedTags shouldContainExactly listOf(listOf("one") to "invoice")
            drafts.state shouldBe emptyMap()
        }

    /** The inline Undo of a tag change expires like a save's, and the entry goes with it. */
    @Test
    fun theInlineUndoOfATagChangeExpires() =
        runTest(dispatcher) {
            val drafts = DocumentDrafts(FakeMetadataWriter(), this)
            drafts.registerEditing("one")

            drafts.tagged("one", "Old", "invoice", added = true)
            runCurrent()
            drafts.state
                .getValue("one")
                .undo
                .shouldNotBeNull()
            advanceUntilIdle()

            drafts.state shouldBe emptyMap()
        }
}
