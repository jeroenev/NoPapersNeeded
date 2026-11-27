package dev.npn.app

import dev.npn.app.ui.library.LibraryViewModel
import dev.npn.core.app.DocListItem
import dev.npn.core.model.ReviewMode
import dev.npn.core.testing.doc
import dev.npn.core.testing.listItem
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TriageViewModelTest : MainDispatcherTest() {
    private fun TestScope.vm(
        items: List<DocListItem> = listOf(listItem("d1"), listItem("d2"), listItem("d3")),
        mode: ReviewMode = ReviewMode.INBOX_WORKFLOW,
    ): LibraryViewModel {
        val session =
            FakeLibrarySession().apply {
                documents.documents.value = items
                reviewConfig.reviewModeFlow.value = mode
            }
        return LibraryViewModel(session, FakeSettingsRepository()).also { collecting(it.content) }
    }

    @Test
    fun startsOnTheFirstDocumentAndReportsTheProgress() =
        runTest(dispatcher) {
            val model = vm(items = listOf(listItem("d1"), listItem("d2"), listItem("d3")))
            advanceUntilIdle()
            model.startTriage()

            val triage = model.triage.value
            triage?.currentId shouldBe "d1"
            triage?.position shouldBe 1
            triage?.total shouldBe 3
            triage?.hasNext shouldBe true
            triage?.hasPrevious shouldBe false
        }

    @Test
    fun aDocumentAlreadyReviewedDoesNotCountAsUnreviewed() =
        runTest(dispatcher) {
            val model =
                vm(
                    items =
                        listOf(
                            listItem("d1"),
                            listItem(doc("d2", reviewed = true)),
                            listItem("d3"),
                        ),
                )
            advanceUntilIdle()
            model.startTriage()

            model.triage.value?.unreviewedRemaining shouldBe 2
            model.triage.value?.reviewed shouldBe emptySet()
        }

    @Test
    fun nextAndPreviousStayInsideTheResultSet() =
        runTest(dispatcher) {
            val model = vm()
            advanceUntilIdle()
            model.startTriage()

            model.triagePrevious()
            model.triage.value?.currentId shouldBe "d1"

            model.triageNext()
            model.triage.value?.currentId shouldBe "d2"
            model.triageNext()
            model.triage.value?.currentId shouldBe "d3"

            model.triageNext()
            model.triage.value?.currentId shouldBe "d3"
        }

    /** Moving on with the arrows is a skip: it writes no reviewed marker, nor any other metadata. */
    @Test
    fun skippingMarksNothingReviewed() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(listItem("d1"), listItem("d2"))
                    reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW
                }
            val model = LibraryViewModel(session, FakeSettingsRepository()).also { collecting(it.content) }
            advanceUntilIdle()
            model.startTriage()

            model.triageNext()
            model.triagePrevious()
            advanceUntilIdle()

            model.triage.value?.currentId shouldBe "d1"
            model.triage.value?.reviewed shouldBe emptySet()
            session.meta.reviewedSets shouldBe emptyList()
            session.meta.batchedEdits shouldBe emptyList()
        }

    @Test
    fun markingReviewedCountsItAndMovesOn() =
        runTest(dispatcher) {
            val model = vm()
            advanceUntilIdle()
            model.startTriage()

            model.triageReviewedAndAdvance(model.triage.value!!.sessionId, "d1")

            model.triage.value?.currentId shouldBe "d2"
            model.triage.value?.reviewed shouldBe setOf("d1")
            model.triage.value?.unreviewedRemaining shouldBe 2
        }

    /** A save that lands after the user moved on counts its document but must not advance the next. */
    @Test
    fun aReviewSaveLandingAfterAMoveDoesNotAdvanceTheNextDocument() =
        runTest(dispatcher) {
            val model = vm()
            advanceUntilIdle()
            model.startTriage()
            model.triageNext()

            model.triageReviewedAndAdvance(model.triage.value!!.sessionId, "d1")

            model.triage.value?.currentId shouldBe "d2"
            model.triage.value?.reviewed shouldBe setOf("d1")
            model.triage.value?.unreviewedRemaining shouldBe 2
        }

    @Test
    fun markingTheLastUnreviewedDocumentFinishesTheMode() =
        runTest(dispatcher) {
            val model = vm(items = listOf(listItem("d1"), listItem("d2")))
            advanceUntilIdle()
            model.startTriage()
            model.triageReviewedAndAdvance(model.triage.value!!.sessionId, "d1")

            model.triage.value?.currentId shouldBe "d2"
            model.triage.value?.nextUnreviewed shouldBe null
            model.triageReviewedAndAdvance(model.triage.value!!.sessionId, "d2")

            model.triage.value shouldBe null
        }

    /**
     * Past the last unreviewed document, "Mark reviewed & next" wraps around to the ones skipped
     * earlier, and finishes only when none is left anywhere.
     */
    @Test
    fun markingReviewedWrapsAroundToDocumentsSkippedEarlier() =
        runTest(dispatcher) {
            val model = vm()
            advanceUntilIdle()
            model.startTriage()
            model.triageNext()
            model.triageNext()

            model.triage.value?.nextUnreviewed shouldBe "d1"
            model.triageReviewedAndAdvance(model.triage.value!!.sessionId, "d3")
            model.triage.value?.currentId shouldBe "d1"

            model.triageReviewedAndAdvance(model.triage.value!!.sessionId, "d1")
            model.triage.value?.currentId shouldBe "d2"
            model.triageReviewedAndAdvance(model.triage.value!!.sessionId, "d2")
            model.triage.value shouldBe null
        }

    /**
     * Over a filter other than the Inbox, reviewed documents sit between the unreviewed ones: marking
     * one reviewed goes on to the next that still needs it, not merely the next in the list.
     */
    @Test
    fun markingReviewedGoesOnToTheNextUnreviewedDocument() =
        runTest(dispatcher) {
            val model =
                vm(
                    items =
                        listOf(
                            listItem("d1"),
                            listItem(doc("d2", reviewed = true)),
                            listItem("d3"),
                        ),
                )
            advanceUntilIdle()
            model.startTriage()

            model.triage.value?.nextUnreviewed shouldBe "d3"
            model.triageReviewedAndAdvance(model.triage.value!!.sessionId, "d1")

            model.triage.value?.currentId shouldBe "d3"
        }

    /** With only reviewed documents left, marking one reviewed finishes the mode. */
    @Test
    fun markingReviewedWithOnlyReviewedDocumentsLeftFinishes() =
        runTest(dispatcher) {
            val model = vm(items = listOf(listItem("d1"), listItem(doc("d2", reviewed = true))))
            advanceUntilIdle()
            model.startTriage()

            model.triage.value?.nextUnreviewed shouldBe null
            model.triageReviewedAndAdvance(model.triage.value!!.sessionId, "d1")

            model.triage.value shouldBe null
        }

    /** A document marked reviewed earlier in the session is not one to go back to. */
    @Test
    fun aDocumentMarkedReviewedInTheSessionIsNotNextAgain() =
        runTest(dispatcher) {
            val model = vm()
            advanceUntilIdle()
            model.startTriage()
            model.triageNext()
            model.triageReviewedAndAdvance(model.triage.value!!.sessionId, "d2")
            model.triagePrevious()
            model.triagePrevious()

            model.triage.value?.currentId shouldBe "d1"
            model.triage.value?.nextUnreviewed shouldBe "d3"
        }

    @Test
    fun aSaveFromAnEarlierSessionCannotChangeAReopenedSession() =
        runTest(dispatcher) {
            val model = vm()
            advanceUntilIdle()
            model.startTriage()
            val oldSession = model.triage.value!!.sessionId
            model.exitTriage()
            model.startTriage()

            model.triageReviewedAndAdvance(oldSession, "d1")

            model.triage.value?.currentId shouldBe "d1"
            model.triage.value?.reviewed shouldBe emptySet()
        }

    @Test
    fun exitingClearsTheMode() =
        runTest(dispatcher) {
            val model = vm()
            advanceUntilIdle()
            model.startTriage()
            model.exitTriage()

            model.triage.value shouldBe null
        }

    @Test
    fun anEmptyResultSetDoesNotStartATriageSession() =
        runTest(dispatcher) {
            val model = vm(items = emptyList())
            advanceUntilIdle()
            model.startTriage()

            model.triage.value shouldBe null
        }

    @Test
    fun startsOnThePreviewedDocumentWhenItIsInTheResultSet() =
        runTest(dispatcher) {
            val model = vm()
            advanceUntilIdle()
            model.preview("d2")
            model.startTriage()

            model.triage.value?.currentId shouldBe "d2"
            model.triage.value?.position shouldBe 2
        }

    /** Untagged-only review has no stored marker for the mode's action to write. */
    @Test
    fun doesNotStartWithoutTheAuthoritativeReviewMarker() =
        runTest(dispatcher) {
            val model = vm(mode = ReviewMode.UNTAGGED_ONLY)
            advanceUntilIdle()
            model.startTriage()

            model.triage.value shouldBe null
        }
}
