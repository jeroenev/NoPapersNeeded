@file:OptIn(ExperimentalCoroutinesApi::class)

package dev.npn.app.ui.ai

import dev.npn.app.FakeLibrarySession
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.app.LibraryFilter
import dev.npn.core.app.LibrarySort
import dev.npn.core.app.SavedView
import dev.npn.core.app.aiedit.EditScope
import dev.npn.core.model.FieldKeys
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * Task presets. A task is a named combination of the same knobs the form exposes by hand, so what
 * matters is that picking one lands on that combination and that editing it away is honest about
 * having done so.
 */
class AiEditTaskTest : MainDispatcherTest() {
    /** A page as it opens: no task chosen, and the size of the scope picks where it runs. */
    private fun model(
        run: FakeAiEditRun = FakeAiEditRun(),
        session: FakeLibrarySession = FakeLibrarySession(),
        selection: List<String> = emptyList(),
    ) = vm(run, session, selection = selection, background = null, task = null)

    @Test
    fun organizingUntaggedDocumentsAsksForTagsOnly() =
        runTest(dispatcher) {
            val model = model()
            collecting(model.ui)
            advanceUntilIdle()

            model.setTask(EditTask.ORGANIZE_UNTAGGED)

            model.ui.value.scope shouldBe EditScope.UNTAGGED
            model.ui.value.includeTags shouldBe true
            model.ui.value.allowNewTags shouldBe true
            model.ui.value.includeTagRemovals shouldBe false
            model.ui.value.fieldOptions.values
                .none { it.enabled } shouldBe true
        }

    @Test
    fun cleaningUpTagsNeverInventsNewOnes() =
        runTest(dispatcher) {
            val model = model()
            collecting(model.ui)
            advanceUntilIdle()

            model.setTask(EditTask.CLEAN_UP_TAGS)

            model.ui.value.scope shouldBe EditScope.ALL
            model.ui.value.includeTags shouldBe true
            model.ui.value.includeTagRemovals shouldBe true
            // The point of a clean-up is fewer distinct tags, so the run may not add to the
            // vocabulary while doing it.
            model.ui.value.allowNewTags shouldBe false
        }

    @Test
    fun fillingFieldsTargetsTheDocumentsMissingThemAndTouchesNoTags() =
        runTest(dispatcher) {
            val model = model()
            collecting(model.ui)
            advanceUntilIdle()

            model.setTask(EditTask.FILL_FIELDS)

            model.ui.value.scope shouldBe EditScope.MISSING_FIELDS
            model.ui.value.includeTags shouldBe false
            model.ui.value.includeTagRemovals shouldBe false
            model.ui.value.fieldOptions.keys shouldContainExactlyInAnyOrder seededAiFieldKeys
            model.ui.value.fieldOptions.values
                .all { it.enabled && it.mode == AiFieldMode.UNFILLED } shouldBe true
        }

    @Test
    fun aTaskPickedBeforeTheRegistryArrivesStillLandsOnItsScope() =
        runTest(dispatcher) {
            val model = model()
            collecting(model.ui)

            // No advanceUntilIdle: the field registry has not answered, so the field-based scope
            // is not available yet and the preset cannot take it.
            model.setTask(EditTask.FILL_FIELDS)
            model.ui.value.scope shouldBe EditScope.UNTAGGED

            advanceUntilIdle()

            // Seeding the fields finishes applying the task rather than leaving the fallback.
            model.ui.value.scope shouldBe EditScope.MISSING_FIELDS
            model.ui.value.fieldOptions.values
                .all { it.enabled } shouldBe true
        }

    @Test
    fun aHandedOverSelectionOutranksThePresetScope() =
        runTest(dispatcher) {
            val model = model(selection = listOf("a"))
            collecting(model.ui)
            advanceUntilIdle()

            // The user already said which documents; the task says what to do to those.
            model.setTask(EditTask.CLEAN_UP_TAGS)
            model.ui.value.scope shouldBe EditScope.SELECTION
        }

    @Test
    fun editingWhatATaskStandsForMakesTheRunCustom() =
        runTest(dispatcher) {
            val model = model()
            collecting(model.ui)
            advanceUntilIdle()
            model.setTask(EditTask.ORGANIZE_UNTAGGED)

            model.setIncludeTagRemovals(true)

            model.ui.value.task shouldBe EditTask.CUSTOM
            model.ui.value.includeTagRemovals shouldBe true
        }

    @Test
    fun choosingWhereToRunOrWhichDocumentsKeepsTheTask() =
        runTest(dispatcher) {
            val session =
                FakeLibrarySession().apply {
                    savedViews.views.value = listOf(SavedView("v1", "Invoices", LibraryFilter(), LibrarySort()))
                }
            val model = model(session = session)
            collecting(model.ui)
            advanceUntilIdle()
            model.setTask(EditTask.FILL_FIELDS)

            // Same job, different paperwork: neither of these changes what the run asks for.
            model.setScope(EditScope.SAVED_VIEW)
            model.setBackground(true)
            model.setBatchSize(3)
            model.setCustomPrompt("prefer the sender on the letterhead")

            model.ui.value.task shouldBe EditTask.FILL_FIELDS
        }

    @Test
    fun rePickingTheCurrentTaskResetsItsEdits() =
        runTest(dispatcher) {
            val model = model()
            collecting(model.ui)
            advanceUntilIdle()
            model.setTask(EditTask.FILL_FIELDS)
            model.setIncludeTags(true)
            model.setFieldMode(FieldKeys.TITLE, AiFieldMode.ALL)
            model.ui.value.task shouldBe EditTask.CUSTOM

            model.setTask(EditTask.FILL_FIELDS)

            model.ui.value.task shouldBe EditTask.FILL_FIELDS
            model.ui.value.includeTags shouldBe false
            model.ui.value.fieldOptions
                .getValue(FieldKeys.TITLE)
                .mode shouldBe AiFieldMode.UNFILLED
        }

    @Test
    fun startDoesNothingUntilATaskIsChosen() =
        runTest(dispatcher) {
            val run = FakeAiEditRun().apply { inScope("a") }
            val model = model(run)
            collecting(model.ui)
            advanceUntilIdle()

            model.start()
            advanceUntilIdle()

            run.requests.shouldBeEmpty()
            model.ui.value.phase shouldBe EditPhase.Idle
        }

    @Test
    fun clearingTheTaskReturnsToTheChooser() =
        runTest(dispatcher) {
            val model = model()
            collecting(model.ui)
            advanceUntilIdle()
            model.setTask(EditTask.CLEAN_UP_TAGS)

            model.clearTask()

            model.ui.value.task shouldBe null
        }
}
