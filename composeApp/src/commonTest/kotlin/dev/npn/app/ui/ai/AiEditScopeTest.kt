@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.app.aiedit.EditScope
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/** Which documents the page offers to the run: the scope it picks and the preview of it. */
class AiEditScopeTest : MainDispatcherTest() {
    private val invoices =
        SavedView(
            id = "v1",
            name = "Invoices",
            filter = LibraryFilter(tagsAll = listOf("invoice")),
            sort = LibrarySort(SortField.Name, ascending = true),
        )

    @Test
    fun thePreviewIsTheScopeTotalAndTheNextBatch() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a", "b", "c") }
            val model = vm(run)
            collecting(model.preview)
            advanceUntilIdle()

            model.setBatchSize(2)
            advanceUntilIdle()

            model.preview.value.total shouldBe 3
            model.preview.value.batch
                .map { it.doc.docId } shouldContainExactly listOf("a", "b")
            run.keys.last().batchSize shouldBe 2
        }

    @Test
    fun thePreviewDropsDocumentsTheRunHasProcessed() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a", "b", "c") }
            val model = vm(run)
            collecting(model.preview)
            advanceUntilIdle()

            run.processed.value = setOf("a", "b")
            advanceUntilIdle()

            model.preview.value.total shouldBe 1
            model.preview.value.batch
                .map { it.doc.docId } shouldContainExactly listOf("c")
        }

    @Test
    fun aHandedOverSelectionIsTheScope() =
        runTest(dispatcher) {
            val model = vm(selection = listOf("a", "c"))
            collecting(model.ui)
            advanceUntilIdle()

            model.ui.value.scope shouldBe EditScope.SELECTION
            model.ui.value.selectionSize shouldBe 2
        }

    @Test
    fun theMissingFieldsScopeLooksForTheEnabledFieldsInRegistryOrder() =
        runTest(dispatcher) {
            val run = FakeAiEditRun()
            val model = vm(run)
            collecting(model.ui)
            collecting(model.preview)
            advanceUntilIdle()

            model.setFieldEnabled(FieldKeys.TITLE, false)
            model.setScope(EditScope.MISSING_FIELDS)
            advanceUntilIdle()

            model.ui.value.scope shouldBe EditScope.MISSING_FIELDS
            run.keys.last().scope shouldBe EditScope.MISSING_FIELDS
            run.keys.last().missing shouldContainExactly
                listOf(FieldKeys.CORRESPONDENT, FieldKeys.DOC_TYPE, FieldKeys.DOC_DATE)

            seededAiFieldKeys.forEach { model.setFieldEnabled(it, false) }
            advanceUntilIdle()
            run.keys
                .last()
                .missing
                .shouldBeEmpty()
        }

    @Test
    fun aSavedViewScopeCarriesTheView() =
        runTest(dispatcher) {
            val run = FakeAiEditRun()
            val session = FakeLibrarySession().apply { savedViews.views.value = listOf(invoices) }
            val model = vm(run, session)
            collecting(model.ui)
            collecting(model.preview)
            advanceUntilIdle()

            model.ui.value.savedViewId shouldBe "v1"
            model.setScope(EditScope.SAVED_VIEW)
            advanceUntilIdle()

            run.keys.last().scope shouldBe EditScope.SAVED_VIEW
            run.keys.last().savedView shouldBe invoices
        }

    @Test
    fun unavailableScopesAreRejected() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { reviewConfig.reviewModeFlow.value = ReviewMode.UNTAGGED_ONLY }
            val model = vm(session = session)
            collecting(model.ui)
            advanceUntilIdle()

            // No hand-off, no saved views, no inbox workflow.
            model.ui.value.reviewQueueAvailable shouldBe false
            model.setScope(EditScope.SELECTION)
            model.ui.value.scope shouldBe EditScope.UNTAGGED
            model.setScope(EditScope.SAVED_VIEW)
            model.ui.value.scope shouldBe EditScope.UNTAGGED
            model.setScope(EditScope.REVIEW_QUEUE)
            model.ui.value.scope shouldBe EditScope.UNTAGGED
        }

    @Test
    fun theReviewQueueScopeComesWithTheInboxWorkflow() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW }
            val model = vm(session = session)
            collecting(model.ui)
            advanceUntilIdle()

            model.ui.value.reviewQueueAvailable shouldBe true
            model.setScope(EditScope.REVIEW_QUEUE)
            model.ui.value.scope shouldBe EditScope.REVIEW_QUEUE
        }

    @Test
    fun startRefusesASavedViewThatIsGone() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a") }
            val session = FakeLibrarySession().apply { savedViews.views.value = listOf(invoices) }
            val model = vm(run, session)
            collecting(model.ui)
            advanceUntilIdle()
            model.setScope(EditScope.SAVED_VIEW)
            // Deleted elsewhere: the scope keeps pointing at a view that no longer exists.
            session.savedViews.views.value = emptyList()
            advanceUntilIdle()
            model.ui.value.startBlocker(ScopePreview(1, emptyList())) shouldBe StartBlocker.NO_SAVED_VIEW

            model.start()
            advanceUntilIdle()

            run.requests.shouldBeEmpty()
            model.ui.value.phase shouldBe EditPhase.Idle
        }

    @Test
    fun startRefusesAnEmptyScope() =
        runTest(dispatcher) {
            val run = FakeAiEditRun()
            val model = vm(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            // The button reads "no documents"; Start must not turn that into a finished run.
            run.requests.shouldBeEmpty()
            model.ui.value.phase shouldBe EditPhase.Idle
        }
}
