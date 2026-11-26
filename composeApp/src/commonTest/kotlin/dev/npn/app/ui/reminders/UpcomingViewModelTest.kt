package dev.npn.app.ui.reminders

import dev.npn.app.FakeLibrarySession
import dev.npn.app.FakeRemindersFacade
import dev.npn.app.MainDispatcherTest
import dev.npn.app.collecting
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.ReminderBucket
import dev.npn.core.app.ReminderSettings
import dev.npn.core.app.UpcomingReminder
import dev.npn.core.testing.doc
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_undo
import no_papers_needed.composeapp.generated.resources.reminders_marked_done
import kotlin.test.Test

/** The Upcoming list: what it shows, and a reminder marked done, with its Undo. */
class UpcomingViewModelTest : MainDispatcherTest() {
    private val due = LocalDate(2026, 11, 30)

    private fun reminder(docId: String) =
        UpcomingReminder(
            doc(docId, title = "Bill $docId"),
            "invoiceDueDate",
            "Due date",
            due,
            ReminderBucket.SOON,
        )

    @Test
    fun aReminderMarkedDoneLeavesTheListAndUndoReopensIt() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.reminders.settingsFlow.value =
                ReminderSettings(enabled = true, fieldKeys = listOf("invoiceDueDate"))
            session.reminders.upcomingFlow.value = listOf(reminder("a"), reminder("b"))
            val vm = UpcomingViewModel(session)
            val states = collecting(vm.state)
            val messages = collecting(vm.messages)
            advanceUntilIdle()

            vm.markDone(reminder("a"))
            advanceUntilIdle()

            states.last()?.items?.map { it.doc.docId } shouldContainExactly listOf("b")
            session.reminders.doneWrites shouldContainExactly
                listOf(FakeRemindersFacade.DoneWrite("a", "invoiceDueDate", due, done = true))
            val undo = messages.single()
            undo.text shouldBe uiText(Res.string.reminders_marked_done, "Bill a")
            undo.action?.label shouldBe uiText(Res.string.library_undo)
            undo.action?.onPerform?.invoke()
            advanceUntilIdle()
            session.reminders.doneWrites.last() shouldBe
                FakeRemindersFacade.DoneWrite("a", "invoiceDueDate", due, done = false)
        }

    @Test
    fun theStateCarriesTheLeadTimeAndWhetherThisDeviceNotifies() =
        runTest(dispatcher) {
            val session = FakeLibrarySession()
            session.reminders.settingsFlow.value =
                ReminderSettings(enabled = true, fieldKeys = listOf("invoiceDueDate"), leadDays = 14)
            session.reminders.notifiesHereFlow.value = false
            val vm = UpcomingViewModel(session)
            val states = collecting(vm.state)
            advanceUntilIdle()

            states.last()?.leadDays shouldBe 14
            states.last()?.notifiesHere shouldBe false
        }
}
