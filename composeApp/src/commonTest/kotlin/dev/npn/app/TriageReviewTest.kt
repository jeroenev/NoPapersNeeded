package dev.npn.app

import dev.npn.app.ui.detail.DocumentDrafts
import dev.npn.app.ui.detail.commitTriageReview
import dev.npn.core.model.FieldKeys
import dev.npn.core.testing.FakeMetadataWriter
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The triage mode's half of the editor's save model: "mark reviewed and next" is one save of the
 * draft and the marker followed by the move, and leaving a document (a J/K move) saves its draft.
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

            drafts.commitTriageReview("one", stored, "New") { moved++ }
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

            drafts.commitTriageReview("one", stored, "New") { moved++ }
            advanceUntilIdle()

            moved shouldBe 0
            drafts.state.getValue("one").error shouldBe "write failed"
        }
}
