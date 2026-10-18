package dev.npn.app

import dev.npn.app.ui.library.LibraryViewModel
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.SortField
import dev.npn.core.app.ViewType
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.maps.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_save_setting_failed
import no_papers_needed.composeapp.generated.resources.library_save_view_failed
import kotlin.test.Test

/** How the library shows its documents: view type, sort and grid width, kept in settings, and saved views. */
class LibrarySortAndViewTest : MainDispatcherTest() {
    private fun vm(
        session: FakeLibrarySession = FakeLibrarySession(),
        settings: FakeSettingsRepository = FakeSettingsRepository(),
    ) = LibraryViewModel(session, settings)

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
    fun applySavedViewSetsFilterSortViewAndMarksOverridden() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository(initialDefaultView = null)
            val model = LibraryViewModel(FakeLibrarySession(), settings)
            collecting(model.content)
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
            runCurrent()
            model.content.value.queryText shouldBe "tax"
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

    /** Sort, view and grid width still apply on screen when saving them fails, and each failure says so. */
    @Test
    fun aFailedSaveOfTheSortViewOrGridWidthIsReportedNotCrashed() =
        runTest(dispatcher) {
            val settings = FakeSettingsRepository().apply { failEdit = { error("read-only file system") } }
            val model = vm(settings = settings)
            val messages = collecting(model.messages.map { it.text })

            model.setSort(SortField.Size, ascending = true)
            model.setViewType(ViewType.GRID)
            model.setGridColumnWidth(220)
            advanceUntilIdle()

            messages shouldContainExactly
                List(3) { failure(Res.string.common_save_setting_failed, "read-only file system") }
            model.ui.value.sort shouldBe LibrarySort(SortField.Size, ascending = true)
            model.ui.value.viewType shouldBe ViewType.GRID
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
    fun renameSavedViewSavesTheNewName() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            val model = vm(session = session)
            val view = SavedView(id = "v1", name = "Old")
            model.renameSavedView(view, "New name")
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
            val messages = collecting(model.messages.map { it.text })
            model.saveCurrentView("Taxes")
            advanceUntilIdle()
            messages shouldContainExactly listOf(failure(Res.string.library_save_view_failed, "disk full"))
        }
}
