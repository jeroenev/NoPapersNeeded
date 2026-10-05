package dev.npn.app

import dev.npn.app.platform.ScanResult
import dev.npn.app.ui.common.UiText
import dev.npn.app.ui.common.uiText
import dev.npn.app.ui.library.LibraryQuery
import dev.npn.app.ui.library.LibraryViewModel
import dev.npn.core.ai.AiProvider
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.app.TagInfo
import dev.npn.core.app.ViewType
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.ReviewMode
import dev.npn.core.testing.listItem
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_scan_capture_failed
import no_papers_needed.composeapp.generated.resources.library_add_tag_failed
import no_papers_needed.composeapp.generated.resources.library_refresh_failed
import no_papers_needed.composeapp.generated.resources.library_save_view_failed
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest : MainDispatcherTest() {
    private fun vm(
        session: FakeLibrarySession = FakeLibrarySession(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ) = LibraryViewModel(session, settings)

    /** Collect the text of the messages [model] emits. */
    private fun TestScope.collectMessages(model: LibraryViewModel): MutableList<UiText> {
        val messages = mutableListOf<UiText>()
        backgroundScope.launch { model.messages.collect { messages += it.text } }
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
            collecting(model.suggestions)
            model.onQueryChange("annual inv")
            advanceTimeBy(99)
            runCurrent()
            model.suggestions.value shouldBe emptyList()
            advanceTimeBy(2)
            runCurrent()
            model.suggestions.value shouldContainExactly listOf("invoice", "inventory")
            model.acceptSuggestion("invoice")
            model.queryText.value shouldBe "annual invoice"
        }

    @Test
    fun shortOrNoResultSuggestionQueryStaysEmpty() =
        runTest(dispatcher) {
            val model = vm()
            collecting(model.suggestions)
            model.onQueryChange("a")
            advanceUntilIdle()
            model.suggestions.value shouldBe emptyList()
            model.onQueryChange("missing")
            advanceUntilIdle()
            model.suggestions.value shouldBe emptyList()
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
            collecting(model.reviewMode)
            advanceUntilIdle()
            model.applyReviewPreset()
            model.ui.value.filter shouldBe LibraryFilter(untaggedOnly = true)
        }

    @Test
    fun reviewPresetInboxWorkflow() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW }
            val model = vm(session = session)
            collecting(model.reviewMode)
            advanceUntilIdle()
            model.applyReviewPreset()
            model.ui.value.filter shouldBe LibraryFilter(unreviewedOrUntagged = true)
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
    fun scannerFailureSurfacesActionableMessage() =
        runTest(dispatcher) {
            val model = vm()
            val messages = collectMessages(model)

            model.importScan(ScanResult.Failed)
            advanceUntilIdle()

            messages shouldBe listOf(uiText(Res.string.common_scan_capture_failed))
        }

    @Test
    fun scannerCancellationProducesNoError() =
        runTest(dispatcher) {
            val model = vm()
            val messages = collectMessages(model)

            model.importScan(ScanResult.Cancelled)
            advanceUntilIdle()

            messages shouldBe emptyList()
        }

    @Test
    fun aRefreshThatFailsSaysSoAndStopsRefreshing() =
        runTest(dispatcher) {
            val session = FakeLibrarySession().apply { ingest.failRescan = { error("folder unavailable") } }
            val model = vm(session = session)
            val messages = collectMessages(model)

            model.refresh()
            advanceUntilIdle()

            messages shouldBe listOf(failure(Res.string.library_refresh_failed, "folder unavailable"))
            model.ui.value.refreshing shouldBe false
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

            val messages = collecting(model.messages.map { it.text })
            runCurrent()
            model.bulkAddTag("finance")
            advanceUntilIdle()
            messages shouldContainExactly listOf(uiText(Res.plurals.library_add_tag_failed, 2, 2))
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
    fun aiEditIsOfferedOnceAiIsConfiguredAndWithdrawnWhenItIsTurnedOff() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository()
            val model = vm(settings = settings)
            backgroundScope.launch { model.aiConfigured.collect {} }
            runCurrent()
            model.aiConfigured.value shouldBe false

            // Configured in Settings while the library is open: no reopen needed.
            settings.edit { it.copy(aiEnabled = true, aiProvider = AiProvider.OLLAMA, aiModel = "qwen3:8b") }
            runCurrent()
            model.aiConfigured.value shouldBe true

            settings.edit { it.copy(aiEnabled = false) }
            runCurrent()
            model.aiConfigured.value shouldBe false
        }

    /** The chips on the documents take their colours from the library's tags, and follow a change. */
    @Test
    fun tagColorsFollowTheLibrarysTags() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.tags.tags.value = listOf(TagInfo("finance", "#ff0000", null, 3))
            val model = vm(session = session)
            backgroundScope.launch { model.tagColors.collect {} }
            runCurrent()
            model.tagColors.value shouldContainExactly mapOf("finance" to "#ff0000")

            session.tags.tags.value = listOf(TagInfo("finance", "#00ff00", null, 3), TagInfo("draft", null, null, 1))
            runCurrent()
            model.tagColors.value shouldContainExactly mapOf("finance" to "#00ff00", "draft" to null)
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
            messages shouldContainExactly listOf(failure(Res.string.library_save_view_failed, "disk full"))
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
            collecting(model.needsReviewCount)
            advanceUntilIdle()
            model.needsReviewCount.value shouldBe 5
            session.reviewConfig.reviewModeFlow.value = ReviewMode.INBOX_WORKFLOW
            advanceUntilIdle()
            model.needsReviewCount.value shouldBe 9
        }

    @Test
    fun documentsObservesWithCurrentFilterAndSort() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(listItem("d1"), listItem("d2"))
                }
            val model = vm(session = session)
            val emitted = collecting(model.documents.map { it?.items?.size ?: 0 })
            advanceUntilIdle()
            model.documents.value
                ?.items
                .orEmpty()
                .map { it.doc.docId } shouldBe listOf("d1", "d2")
            session.documents.observedFilters
                .first()
                .first shouldBe LibraryFilter()
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
            collecting(model.documents)
            advanceUntilIdle()
            model.documents.value?.query shouldBe LibraryQuery(LibraryFilter(), LibrarySort())

            model.setSort(SortField.Field(FieldKeys.DOC_DATE), ascending = false)
            advanceUntilIdle()
            model.documents.value?.query shouldBe
                LibraryQuery(LibraryFilter(), LibrarySort(SortField.Field(FieldKeys.DOC_DATE), ascending = false))
        }

    @Test
    fun updatingTheCurrentResultSetKeepsItsQuery() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    documents.documents.value = listOf(listItem("d1"))
                }
            val model = vm(session = session)
            val queries = collecting(model.documents.map { it?.query })
            advanceUntilIdle()

            // A document arriving under the same filter/sort (import, rescan) is not a new query.
            session.documents.documents.value = listOf(listItem("d1"), listItem("d2"))
            advanceUntilIdle()
            model.documents.value
                ?.items
                ?.size shouldBe 2
            queries.filterNotNull().distinct() shouldBe listOf(LibraryQuery(LibraryFilter(), LibrarySort()))
        }
}
