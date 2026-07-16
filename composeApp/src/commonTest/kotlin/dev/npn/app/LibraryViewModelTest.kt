package dev.npn.app

import dev.npn.app.ui.library.ImportSummary
import dev.npn.app.ui.library.LibraryViewModel
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.app.ViewType
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest : MainDispatcherTest() {

    private fun vm(
        session: FakeLibrarySession = FakeLibrarySession(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ) = LibraryViewModel(session, settings)

    // ---- query debounce / filter sync ----

    @Test
    fun queryTextReflectsKeystrokesImmediately() = runTest(dispatcher) {
        val model = vm()
        model.onQueryChange("invoice")
        // queryText mirrors the raw input without waiting for the debounce.
        model.queryText.value shouldBe "invoice"
        // The filter has NOT been updated yet (debounce not elapsed).
        model.ui.value.filter.query.shouldBeNull()
    }

    @Test
    fun queryFlowsIntoFilterAfterDebounce() = runTest(dispatcher) {
        val model = vm()
        model.onQueryChange("invoice")
        advanceTimeBy(299)
        runCurrent()
        model.ui.value.filter.query.shouldBeNull()
        advanceTimeBy(2)
        runCurrent()
        model.ui.value.filter.query shouldBe "invoice"
    }

    @Test
    fun blankQueryBecomesNullFilter() = runTest(dispatcher) {
        val model = vm()
        model.onQueryChange("   ")
        advanceUntilIdle()
        model.ui.value.filter.query.shouldBeNull()
    }

    // ---- viewOverridden race guard ----

    @Test
    fun defaultViewSeedAppliesWhenUserHasNotChosen() = runTest(dispatcher) {
        val settings = FakeSettingsRepository(initialDefaultView = ViewType.GRID)
        val model = vm(settings = settings)
        advanceUntilIdle()
        model.ui.value.viewType shouldBe ViewType.GRID
    }

    @Test
    fun explicitViewChoiceIsNotClobberedByLateDefaultSeed() = runTest(dispatcher) {
        val settings = DeferredDefaultViewSettings()
        val model = LibraryViewModel(FakeLibrarySession(), settings)
        // User picks GRID before the async default seed (LIST) arrives.
        model.setViewType(ViewType.GRID)
        runCurrent()
        model.ui.value.viewType shouldBe ViewType.GRID
        // Now the deferred default arrives; it must NOT clobber the explicit choice.
        settings.emitDefaultView(ViewType.LIST)
        advanceUntilIdle()
        model.ui.value.viewType shouldBe ViewType.GRID
    }

    @Test
    fun setViewTypePersistsChoice() = runTest(dispatcher) {
        val settings = FakeSettingsRepository()
        val model = vm(settings = settings)
        model.setViewType(ViewType.GRID)
        advanceUntilIdle()
        settings.setDefaultViews shouldContainExactly listOf(ViewType.GRID)
    }

    // ---- applyReviewPreset ----

    @Test
    fun reviewPresetUntaggedOnly() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { reviewModeFlow.value = ReviewMode.UNTAGGED_ONLY }
        val model = vm(session = session)
        // reviewMode is a WhileSubscribed StateFlow; keep a live collector so .value tracks upstream.
        val job = launch { model.reviewMode.collect {} }
        advanceUntilIdle()
        model.applyReviewPreset()
        model.ui.value.filter shouldBe LibraryFilter(untaggedOnly = true)
        job.cancel()
    }

    @Test
    fun reviewPresetInboxWorkflow() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW }
        val model = vm(session = session)
        val job = launch { model.reviewMode.collect {} }
        advanceUntilIdle()
        model.applyReviewPreset()
        model.ui.value.filter shouldBe LibraryFilter(unreviewedOrUntagged = true)
        job.cancel()
    }

    @Test
    fun reviewPresetClearsQueryInput() = runTest(dispatcher) {
        val model = vm()
        model.onQueryChange("stale")
        advanceUntilIdle()
        model.applyReviewPreset()
        model.queryText.value shouldBe ""
    }

    // ---- import tallying ----

    @Test
    fun importScannedPdfImportedMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            importOutcomes = listOf(ImportOutcome.Imported("a", "a.pdf"))
        }
        val model = vm(session = session)
        var msg: String? = null
        model.importScannedPdf(byteArrayOf(1, 2, 3)) { msg = it }
        advanceUntilIdle()
        msg shouldBe "Scan imported"
    }

    @Test
    fun importScannedPdfDuplicateMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            importOutcomes = listOf(ImportOutcome.Duplicate("a", "a.pdf"))
        }
        val model = vm(session = session)
        var msg: String? = null
        model.importScannedPdf(byteArrayOf(1)) { msg = it }
        advanceUntilIdle()
        msg shouldBe "Scan already in library"
    }

    @Test
    fun importScannedPdfFailureSurfacesMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { failImport = { error("disk full") } }
        val model = vm(session = session)
        var msg: String? = null
        model.importScannedPdf(byteArrayOf(1)) { msg = it }
        advanceUntilIdle()
        msg shouldBe "Scan import failed: disk full"
    }

    @Test
    fun importSummaryMessageFormatsCounts() {
        ImportSummary(3, 0, 0).message shouldBe "3 imported"
        ImportSummary(2, 1, 0).message shouldBe "2 imported, 1 duplicate"
        ImportSummary(2, 1, 3).message shouldBe "2 imported, 1 duplicate, 3 failed"
    }

    // ---- bulk batched op failure handling ----

    @Test
    fun bulkAddTagSuccessClearsSelection() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.startSelection("d1")
        model.toggleSelection("d2")
        model.bulkAddTag("finance")
        advanceUntilIdle()
        session.meta.addedTags shouldContainExactly listOf(listOf("d1", "d2") to "finance")
        model.ui.value.selected shouldBe emptySet()
        model.ui.value.selectionMode shouldBe false
    }

    @Test
    fun bulkAddTagFailureKeepsSelectionAndEmitsMessage() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { meta.failBatchOps = { error("write failed") } }
        val model = vm(session = session)
        model.startSelection("d1")
        model.toggleSelection("d2")

        val messages = mutableListOf<String>()
        val collector = launch { model.messages.collect { messages += it } }
        runCurrent()
        model.bulkAddTag("finance")
        advanceUntilIdle()
        collector.cancel()
        messages shouldContainExactly listOf("Could not add tag 2 document(s)")
        // Selection preserved so the user can retry.
        model.ui.value.selected shouldBe setOf("d1", "d2")
        model.ui.value.selectionMode shouldBe true
    }

    @Test
    fun bulkSetReviewedNoSelectionIsNoOp() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.bulkSetReviewed(true)
        advanceUntilIdle()
        session.meta.reviewedSets shouldBe emptyList()
    }

    // ---- per-doc bulk trash ----

    @Test
    fun bulkTrashAllSucceedClearsSelection() = runTest(dispatcher) {
        val session = FakeLibrarySession()
        val model = vm(session = session)
        model.startSelection("d1")
        model.toggleSelection("d2")
        model.toggleSelection("d3")
        model.bulkTrash()
        advanceUntilIdle()
        session.meta.trashedDocs shouldContainExactlyInAnyOrder listOf("d1", "d2", "d3")
        model.ui.value.selected shouldBe emptySet()
        model.ui.value.selectionMode shouldBe false
    }

    @Test
    fun bulkTrashPartialFailureKeepsOnlyFailedIdsSelected() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply { meta.failTrashDocIds = setOf("d2") }
        val model = vm(session = session)
        model.startSelection("d1")
        model.toggleSelection("d2")
        model.toggleSelection("d3")

        val messages = mutableListOf<String>()
        val collector = launch { model.messages.collect { messages += it } }
        runCurrent()
        model.bulkTrash()
        advanceUntilIdle()
        collector.cancel()
        messages shouldContainExactly listOf("Could not trash 1 of 3 document(s)")
        // d1 and d3 succeeded; only d2 kept selected for retry.
        session.meta.trashedDocs shouldContainExactlyInAnyOrder listOf("d1", "d3")
        model.ui.value.selected shouldBe setOf("d2")
        model.ui.value.selectionMode shouldBe true
    }

    // ---- selection state machine ----

    @Test
    fun startSelectionEntersSelectionMode() = runTest(dispatcher) {
        val model = vm()
        model.startSelection("d1")
        model.ui.value.selectionMode shouldBe true
        model.ui.value.selected shouldBe setOf("d1")
    }

    @Test
    fun toggleSelectionAddsAndRemoves() = runTest(dispatcher) {
        val model = vm()
        model.toggleSelection("d1")
        model.ui.value.selected shouldBe setOf("d1")
        model.toggleSelection("d2")
        model.ui.value.selected shouldBe setOf("d1", "d2")
        model.toggleSelection("d1")
        model.ui.value.selected shouldBe setOf("d2")
    }

    @Test
    fun togglingLastSelectionExitsSelectionMode() = runTest(dispatcher) {
        val model = vm()
        model.toggleSelection("d1")
        model.ui.value.selectionMode shouldBe true
        model.toggleSelection("d1")
        model.ui.value.selectionMode shouldBe false
        model.ui.value.selected shouldBe emptySet()
    }

    @Test
    fun clearSelectionResetsState() = runTest(dispatcher) {
        val model = vm()
        model.startSelection("d1")
        model.toggleSelection("d2")
        model.clearSelection()
        model.ui.value.selectionMode shouldBe false
        model.ui.value.selected shouldBe emptySet()
    }

    // ---- filter/sort plumbing ----

    @Test
    fun setFilterSyncsQueryInput() = runTest(dispatcher) {
        val model = vm()
        model.setFilter(LibraryFilter(query = "taxes", untaggedOnly = true))
        model.ui.value.filter shouldBe LibraryFilter(query = "taxes", untaggedOnly = true)
        model.queryText.value shouldBe "taxes"
        model.ui.value.bannerDismissed shouldBe false
    }

    @Test
    fun clearFilterResetsFilterAndQuery() = runTest(dispatcher) {
        val model = vm()
        model.setFilter(LibraryFilter(query = "x", docType = "letter"))
        model.clearFilter()
        model.ui.value.filter shouldBe LibraryFilter()
        model.queryText.value shouldBe ""
    }

    @Test
    fun applySavedViewSetsFilterSortViewAndMarksOverridden() = runTest(dispatcher) {
        val settings = DeferredDefaultViewSettings()
        val model = LibraryViewModel(FakeLibrarySession(), settings)
        val view = SavedView(
            id = "v1",
            name = "Taxes",
            filter = LibraryFilter(query = "tax", docType = "form"),
            viewType = ViewType.GRID,
        )
        model.applySavedView(view)
        model.ui.value.filter shouldBe view.filter
        model.ui.value.viewType shouldBe ViewType.GRID
        model.queryText.value shouldBe "tax"
        // The late default must not override the applied saved view.
        settings.emitDefaultView(ViewType.LIST)
        advanceUntilIdle()
        model.ui.value.viewType shouldBe ViewType.GRID
    }

    @Test
    fun setSortUpdatesState() = runTest(dispatcher) {
        val model = vm()
        model.setSort(SortField.NAME, ascending = true)
        model.ui.value.sort.field shouldBe SortField.NAME
        model.ui.value.sort.ascending shouldBe true
    }

    // ---- needsReviewCount switches source by review mode ----

    @Test
    fun needsReviewCountFollowsReviewMode() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.untaggedCount.value = 5
            documents.unreviewedOrUntaggedCount.value = 9
            reviewModeFlow.value = ReviewMode.UNTAGGED_ONLY
        }
        val model = vm(session = session)
        val job = launch { model.needsReviewCount.collect {} }
        advanceUntilIdle()
        model.needsReviewCount.value shouldBe 5
        session.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW
        advanceUntilIdle()
        model.needsReviewCount.value shouldBe 9
        job.cancel()
    }

    // ---- documents subscription passes filter/sort through ----

    @Test
    fun documentsObservesWithCurrentFilterAndSort() = runTest(dispatcher) {
        val session = FakeLibrarySession().apply {
            documents.documents.value = listOf(listItem("d1"), listItem("d2"))
        }
        val model = vm(session = session)
        val emitted = mutableListOf<Int>()
        val job = launch { model.documents.collect { emitted += it.size } }
        advanceUntilIdle()
        model.documents.value.map { it.doc.docId } shouldBe listOf("d1", "d2")
        session.documents.observedFilters.first().first shouldBe LibraryFilter()
        job.cancel()
    }
}
