package dev.npn.app

import dev.npn.app.ui.library.ImportSummary
import dev.npn.app.ui.library.LibraryQuery
import dev.npn.app.ui.library.LibraryViewModel
import dev.npn.core.app.ImportOutcome
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.app.ViewType
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
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

    /** Collect messages emitted by [model] into a list of resolved English strings. */
    private fun TestScope.collectMessages(model: LibraryViewModel): MutableList<String> {
        val messages = mutableListOf<String>()
        backgroundScope.launch { model.messages.collect { messages += it.resolveEnglish() } }
        return messages
    }

    @Test
    fun queryTextReflectsKeystrokesImmediately() =
        runTest(dispatcher) {
            val model = vm()
            model.onQueryChange("invoice")
            // queryText mirrors the raw input without waiting for the debounce.
            model.queryText.value shouldBe "invoice"
            // The filter has NOT been updated yet (debounce not elapsed).
            model.ui.value.filter.query
                .shouldBeNull()
        }

    @Test
    fun queryFlowsIntoFilterAfterDebounce() =
        runTest(dispatcher) {
            val model = vm()
            model.onQueryChange("invoice")
            advanceTimeBy(299)
            runCurrent()
            model.ui.value.filter.query
                .shouldBeNull()
            advanceTimeBy(2)
            runCurrent()
            model.ui.value.filter.query shouldBe "invoice"
        }

    @Test
    fun blankQueryBecomesNullFilter() =
        runTest(dispatcher) {
            val model = vm()
            model.onQueryChange("   ")
            advanceUntilIdle()
            model.ui.value.filter.query
                .shouldBeNull()
        }

    @Test
    fun suggestionsUseActiveTokenAndExpandAsynchronously() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.suggestionsList = listOf("invoice", "inventory")
                }
            val model = vm(session = session)
            val job = launch { model.suggestions.collect {} }
            model.onQueryChange("annual inv")
            advanceTimeBy(99)
            runCurrent()
            model.suggestions.value shouldBe emptyList()
            advanceTimeBy(2)
            runCurrent()
            model.suggestions.value shouldContainExactly listOf("invoice", "inventory")
            model.acceptSuggestion("invoice")
            model.queryText.value shouldBe "annual invoice"
            job.cancel()
        }

    @Test
    fun shortOrNoResultSuggestionQueryStaysEmpty() =
        runTest(dispatcher) {
            val model = vm()
            val job = launch { model.suggestions.collect {} }
            model.onQueryChange("a")
            advanceUntilIdle()
            model.suggestions.value shouldBe emptyList()
            model.onQueryChange("missing")
            advanceUntilIdle()
            model.suggestions.value shouldBe emptyList()
            job.cancel()
        }

    @Test
    fun defaultViewSeedAppliesWhenUserHasNotChosen() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository(initialDefaultView = ViewType.GRID)
            val model = vm(settings = settings)
            advanceUntilIdle()
            model.ui.value.viewType shouldBe ViewType.GRID
        }

    @Test
    fun explicitViewChoiceIsNotClobberedByLateDefaultSeed() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository(initialDefaultView = null)
            val model = LibraryViewModel(FakeLibrarySession(), settings)
            // User picks GRID before the async default seed (LIST) arrives.
            model.setViewType(ViewType.GRID)
            runCurrent()
            model.ui.value.viewType shouldBe ViewType.GRID
            // Now the deferred default arrives; it must NOT clobber the explicit choice.
            settings.updateSettings { it.copy(defaultViewType = ViewType.LIST) }
            advanceUntilIdle()
            model.ui.value.viewType shouldBe ViewType.GRID
        }

    @Test
    fun setViewTypePersistsChoice() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            model.setViewType(ViewType.GRID)
            advanceUntilIdle()
            settings.settingsFlow.value?.defaultViewType shouldBe ViewType.GRID
        }

    @Test
    fun reviewPresetUntaggedOnly() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { reviewConfig.reviewModeFlow.value = ReviewMode.UNTAGGED_ONLY }
            val model = vm(session = session)
            // reviewMode is a WhileSubscribed StateFlow; keep a live collector so .value tracks upstream.
            val job = launch { model.reviewMode.collect {} }
            advanceUntilIdle()
            model.applyReviewPreset()
            model.ui.value.filter shouldBe LibraryFilter(untaggedOnly = true)
            job.cancel()
        }

    @Test
    fun reviewPresetInboxWorkflow() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW }
            val model = vm(session = session)
            val job = launch { model.reviewMode.collect {} }
            advanceUntilIdle()
            model.applyReviewPreset()
            model.ui.value.filter shouldBe LibraryFilter(unreviewedOrUntagged = true)
            job.cancel()
        }

    @Test
    fun reviewPresetClearsQueryInput() =
        runTest(dispatcher) {
            val model = vm()
            model.onQueryChange("stale")
            advanceUntilIdle()
            model.applyReviewPreset()
            model.queryText.value shouldBe ""
        }

    @Test
    fun importScannedPdfImportedMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ingest.importOutcomes = listOf(ImportOutcome.Imported("a", "a.pdf"))
                }
            val model = vm(session = session)
            val messages = collectMessages(model)
            model.importScannedPdf(byteArrayOf(1, 2, 3))
            advanceUntilIdle()
            messages shouldBe listOf("Scan imported")
        }

    @Test
    fun importScannedPdfDuplicateMessage() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ingest.importOutcomes = listOf(ImportOutcome.Duplicate("a", "a.pdf"))
                }
            val model = vm(session = session)
            val messages = collectMessages(model)
            model.importScannedPdf(byteArrayOf(1))
            advanceUntilIdle()
            messages shouldBe listOf("Scan already in library")
        }

    @Test
    fun importScannedPdfFailureSurfacesMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ingest.failImport = { error("disk full") } }
            val model = vm(session = session)
            val messages = collectMessages(model)
            model.importScannedPdf(byteArrayOf(1))
            advanceUntilIdle()
            messages shouldBe listOf("Scan import failed: disk full")
        }

    @Test
    fun importScannedPdfFailureUsesUnknownErrorFallback() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    ingest.failImport = { throw object : Throwable() {} }
                }
            val model = vm(session = session)
            val messages = collectMessages(model)
            model.importScannedPdf(byteArrayOf(1))
            advanceUntilIdle()
            messages shouldBe listOf("Scan import failed: unknown error")
        }

    @Test
    fun importSummaryMessageFormatsCounts() =
        runTest(dispatcher) {
            ImportSummary(3, 0, 0).asUiText().resolveEnglish() shouldBe "3 imported"
            ImportSummary(2, 1, 0).asUiText().resolveEnglish() shouldBe "2 imported, 1 duplicate"
            ImportSummary(2, 1, 3).asUiText().resolveEnglish() shouldBe "2 imported, 1 duplicate, 3 failed"
        }

    @Test
    fun bulkAddTagSuccessClearsSelection() =
        runTest(dispatcher) {
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
    fun bulkAddTagFailureKeepsSelectionAndEmitsMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { meta.failBatchOps = { error("write failed") } }
            val model = vm(session = session)
            model.startSelection("d1")
            model.toggleSelection("d2")

            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }
            runCurrent()
            model.bulkAddTag("finance")
            advanceUntilIdle()
            collector.cancel()
            messages shouldContainExactly listOf("Could not add tag 2 documents")
            // Selection preserved so the user can retry.
            model.ui.value.selected shouldBe setOf("d1", "d2")
            model.ui.value.selectionMode shouldBe true
        }

    @Test
    fun bulkSetReviewedNoSelectionIsNoOp() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.bulkSetReviewed(true)
            advanceUntilIdle()
            session.meta.reviewedSets shouldBe emptyList()
        }

    @Test
    fun bulkTrashAllSucceedClearsSelection() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.toggleSelection("d3")
            model.bulkTrash()
            advanceUntilIdle()
            session.docLifecycle.trashedDocs shouldContainExactlyInAnyOrder listOf("d1", "d2", "d3")
            model.ui.value.selected shouldBe emptySet()
            model.ui.value.selectionMode shouldBe false
        }

    @Test
    fun bulkTrashPartialFailureKeepsOnlyFailedIdsSelected() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { docLifecycle.failTrashDocIds = setOf("d2") }
            val model = vm(session = session)
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.toggleSelection("d3")

            val messages = mutableListOf<String>()
            val collector = launch { model.messages.collect { messages += it.resolveEnglish() } }
            runCurrent()
            model.bulkTrash()
            advanceUntilIdle()
            collector.cancel()
            messages shouldContainExactly listOf("Could not trash 1 of 3 documents")
            // d1 and d3 succeeded; only d2 kept selected for retry.
            session.docLifecycle.trashedDocs shouldContainExactlyInAnyOrder listOf("d1", "d3")
            model.ui.value.selected shouldBe setOf("d2")
            model.ui.value.selectionMode shouldBe true
        }

    @Test
    fun startSelectionEntersSelectionMode() =
        runTest(dispatcher) {
            val model = vm()
            model.startSelection("d1")
            model.ui.value.selectionMode shouldBe true
            model.ui.value.selected shouldBe setOf("d1")
        }

    @Test
    fun toggleSelectionAddsAndRemoves() =
        runTest(dispatcher) {
            val model = vm()
            model.toggleSelection("d1")
            model.ui.value.selected shouldBe setOf("d1")
            model.toggleSelection("d2")
            model.ui.value.selected shouldBe setOf("d1", "d2")
            model.toggleSelection("d1")
            model.ui.value.selected shouldBe setOf("d2")
        }

    @Test
    fun togglingLastSelectionExitsSelectionMode() =
        runTest(dispatcher) {
            val model = vm()
            model.toggleSelection("d1")
            model.ui.value.selectionMode shouldBe true
            model.toggleSelection("d1")
            model.ui.value.selectionMode shouldBe false
            model.ui.value.selected shouldBe emptySet()
        }

    @Test
    fun clearSelectionResetsState() =
        runTest(dispatcher) {
            val model = vm()
            model.startSelection("d1")
            model.toggleSelection("d2")
            model.clearSelection()
            model.ui.value.selectionMode shouldBe false
            model.ui.value.selected shouldBe emptySet()
        }

    @Test
    fun setFilterSyncsQueryInput() =
        runTest(dispatcher) {
            val model = vm()
            model.setFilter(LibraryFilter(query = "taxes", untaggedOnly = true))
            model.ui.value.filter shouldBe LibraryFilter(query = "taxes", untaggedOnly = true)
            model.queryText.value shouldBe "taxes"
            model.ui.value.bannerDismissed shouldBe false
        }

    @Test
    fun clearFilterResetsFilterAndQuery() =
        runTest(dispatcher) {
            val model = vm()
            model.setFilter(LibraryFilter(query = "x", fieldEquals = mapOf(FieldKeys.DOC_TYPE to "letter")))
            model.clearFilter()
            model.ui.value.filter shouldBe LibraryFilter()
            model.queryText.value shouldBe ""
        }

    @Test
    fun applySavedViewSetsFilterSortViewAndMarksOverridden() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository(initialDefaultView = null)
            val model = LibraryViewModel(FakeLibrarySession(), settings)
            val view =
                SavedView(
                    id = "v1",
                    name = "Taxes",
                    filter = LibraryFilter(query = "tax", fieldEquals = mapOf(FieldKeys.DOC_TYPE to "form")),
                    viewType = ViewType.GRID,
                )
            model.applySavedView(view)
            model.ui.value.filter shouldBe view.filter
            model.ui.value.viewType shouldBe ViewType.GRID
            model.queryText.value shouldBe "tax"
            // The late default must not override the applied saved view.
            settings.updateSettings { it.copy(defaultViewType = ViewType.LIST) }
            advanceUntilIdle()
            model.ui.value.viewType shouldBe ViewType.GRID
        }

    @Test
    fun setSortUpdatesState() =
        runTest(dispatcher) {
            val model = vm()
            model.setSort(SortField.Name, ascending = true)
            model.ui.value.sort.field shouldBe SortField.Name
            model.ui.value.sort.ascending shouldBe true
        }

    @Test
    fun setSortPersistsToSettings() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            model.setSort(SortField.Size, ascending = true)
            advanceUntilIdle()
            settings.settingsFlow.value?.librarySort shouldBe LibrarySort(SortField.Size, ascending = true)
        }

    @Test
    fun persistedSortSeedAppliesWhenUserHasNotChosen() =
        runTest(dispatcher) {
            val settings =
                FakeSettingsRepository().apply {
                    updateSettings { it.copy(librarySort = LibrarySort(SortField.Name, ascending = true)) }
                }
            val model = vm(settings = settings)
            advanceUntilIdle()
            model.ui.value.sort shouldBe LibrarySort(SortField.Name, ascending = true)
        }

    @Test
    fun explicitSortChoiceIsNotClobberedByLateSeed() =
        runTest(dispatcher) {
            val settings =
                FakeSettingsRepository().apply {
                    updateSettings { it.copy(librarySort = LibrarySort(SortField.Name, ascending = true)) }
                }
            val model = vm(settings = settings)
            // User sorts before the async seed runs.
            model.setSort(SortField.Size, ascending = false)
            advanceUntilIdle()
            model.ui.value.sort shouldBe LibrarySort(SortField.Size, ascending = false)
        }

    @Test
    fun setGridColumnWidthPersistsToSettings() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            model.setGridColumnWidth(220)
            advanceUntilIdle()
            settings.settingsFlow.value?.gridColumnWidth shouldBe 220
        }

    @Test
    fun saveCurrentViewCapturesLiveFilterAndSort() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.setFilter(LibraryFilter(query = "tax", fieldEquals = mapOf(FieldKeys.DOC_TYPE to "form")))
            model.setSort(SortField.Name, ascending = true)
            model.saveCurrentView("Taxes")
            advanceUntilIdle()
            val saved = session.savedViews.saved.single()
            saved.name shouldBe "Taxes"
            saved.filter shouldBe LibraryFilter(query = "tax", fieldEquals = mapOf(FieldKeys.DOC_TYPE to "form"))
            saved.sort shouldBe LibrarySort(SortField.Name, ascending = true)
        }

    @Test
    fun renameSavedViewTrimsWhitespace() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val view = SavedView(id = "v1", name = "Old")
            model.renameSavedView(view, "  New name  ")
            advanceUntilIdle()
            session.savedViews.saved.single() shouldBe view.copy(name = "New name")
        }

    @Test
    fun toggleSavedViewPinFlipsPinnedState() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val view = SavedView(id = "v1", name = "Pinned?", pinned = false)
            model.toggleSavedViewPin(view)
            advanceUntilIdle()
            session.savedViews.saved.single() shouldBe view.copy(pinned = true)
        }

    @Test
    fun deleteSavedViewPassesViewId() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            model.deleteSavedView(SavedView(id = "v1", name = "Doomed"))
            advanceUntilIdle()
            session.savedViews.deleted shouldContainExactly listOf("v1")
        }

    @Test
    fun saveCurrentViewFailureSurfacesMessage() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { savedViews.failSave = { error("disk full") } }
            val model = vm(session = session)
            val messages = collectMessages(model)
            model.saveCurrentView("Taxes")
            advanceUntilIdle()
            messages shouldContainExactly listOf("Could not update: disk full")
        }

    @Test
    fun needsReviewCountFollowsReviewMode() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.untaggedCount.value = 5
                    documents.unreviewedOrUntaggedCount.value = 9
                    reviewConfig.reviewModeFlow.value = ReviewMode.UNTAGGED_ONLY
                }
            val model = vm(session = session)
            val job = launch { model.needsReviewCount.collect {} }
            advanceUntilIdle()
            model.needsReviewCount.value shouldBe 5
            session.reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW
            advanceUntilIdle()
            model.needsReviewCount.value shouldBe 9
            job.cancel()
        }

    @Test
    fun documentsObservesWithCurrentFilterAndSort() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(listItem("d1"), listItem("d2"))
                }
            val model = vm(session = session)
            val emitted = mutableListOf<Int>()
            val job = launch { model.documents.collect { emitted += it?.items?.size ?: 0 } }
            advanceUntilIdle()
            model.documents.value
                ?.items
                .orEmpty()
                .map { it.doc.docId } shouldBe listOf("d1", "d2")
            session.documents.observedFilters
                .first()
                .first shouldBe LibraryFilter()
            job.cancel()
        }

    // The library screen resets the scroll position when this query changes, so it may only
    // change together with the items: never before the new ones arrive, and never on an update
    // of the current result set.

    @Test
    fun resultsCarryTheFilterAndSortTheyWereQueriedWith() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(listItem("d1"), listItem("d2"))
                }
            val model = vm(session = session)
            val job = launch { model.documents.collect {} }
            advanceUntilIdle()
            model.documents.value?.query shouldBe LibraryQuery(LibraryFilter(), LibrarySort())

            model.setSort(SortField.Field(FieldKeys.DOC_DATE), ascending = false)
            advanceUntilIdle()
            model.documents.value?.query shouldBe
                LibraryQuery(LibraryFilter(), LibrarySort(SortField.Field(FieldKeys.DOC_DATE), ascending = false))
            job.cancel()
        }

    @Test
    fun updatingTheCurrentResultSetKeepsItsQuery() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(listItem("d1"))
                }
            val model = vm(session = session)
            val queries = mutableListOf<LibraryQuery?>()
            val job = launch { model.documents.collect { queries += it?.query } }
            advanceUntilIdle()

            // A document arriving under the same filter/sort (import, rescan) is not a new query.
            session.documents.documents.value = listOf(listItem("d1"), listItem("d2"))
            advanceUntilIdle()
            model.documents.value
                ?.items
                ?.size shouldBe 2
            queries.filterNotNull().distinct() shouldBe listOf(LibraryQuery(LibraryFilter(), LibrarySort()))
            job.cancel()
        }
}
