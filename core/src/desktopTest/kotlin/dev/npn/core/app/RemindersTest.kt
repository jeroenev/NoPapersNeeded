package dev.npn.core.app

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.pdf.PdfFixtures
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlin.test.Test

private fun LocalDate.plusDays(days: Int): LocalDate = plus(DatePeriod(days = days))

class RemindersTest {
    private val zone = TimeZone.currentSystemDefault()

    @Test
    fun theSettingsRoundTripThroughTheJournalAndKeepOnlyDateFields() =
        appSessionTest { rig ->
            val reminders = rig.session.reminders
            reminders.setFieldKeys(listOf(FieldKeys.DOC_DATE, FieldKeys.TITLE))
            reminders.setLeadDays(3)
            reminders.setTimeOfDay(LocalTime(8, 30))
            reminders.setEnabled(true)

            val settings = reminders.settings.first()
            settings.enabled shouldBe true
            settings.fieldKeys shouldContainExactly listOf(FieldKeys.DOC_DATE)
            settings.leadDays shouldBe 3
            settings.timeOfDay shouldBe LocalTime(8, 30)

            // The same library read by another device has the same reminder settings.
            val (second, _) = rig.secondSession(backgroundScope)
            second.reminders.settings.first() shouldBe settings
        }

    @Test
    fun enablingWithNoFieldChosenStartsFromTheSeededDocumentDate() =
        appSessionTest { rig ->
            rig.session.reminders.setEnabled(true)

            val settings =
                rig.session.reminders.settings
                    .first()
            settings.enabled shouldBe true
            settings.fieldKeys shouldContainExactly listOf(FieldKeys.DOC_DATE)
            settings.isActive shouldBe true
        }

    @Test
    fun aReminderIsClaimedOnceAndOnlyForLiveDocuments() =
        appSessionTest { rig ->
            val today = ReminderWindow.today(rig.clock.now, zone)
            val due = today.plusDays(2)
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.writePdf("b.pdf", PdfFixtures.embeddedTextPdf("bravo"))
            rig.session.rescan()
            val a = rig.docId("a.pdf")
            val b = rig.docId("b.pdf")
            rig.session.meta.setField(a, FieldKeys.DOC_DATE, due.toString())
            rig.session.meta.setField(b, FieldKeys.DOC_DATE, due.toString())
            rig.session.docLifecycle.trashDoc(b)

            val reminders = rig.session.reminders
            reminders.setFieldKeys(listOf(FieldKeys.DOC_DATE))
            reminders.setEnabled(true)
            reminders.setTimeOfDay(LocalTime(0, 0))
            rig.clock.now = ReminderWindow.triggerAt(today, LocalTime(10, 0), zone)

            reminders
                .claimDueReminders()
                .map { it.docId } shouldContainExactly listOf(a)
            // The same reminder is not repeated on the next check.
            reminders.claimDueReminders().shouldBeEmpty()

            // A new due date is a new reminder.
            rig.session.meta.setField(a, FieldKeys.DOC_DATE, due.plusDays(1).toString())
            reminders.claimDueReminders().map { it.docId } shouldContainExactly listOf(a)
        }

    @Test
    fun theNotificationWindowStopsAtTheLeadTime() =
        appSessionTest { rig ->
            val today = ReminderWindow.today(rig.clock.now, zone)
            rig.writePdf("soon.pdf", PdfFixtures.embeddedTextPdf("soon"))
            rig.writePdf("later.pdf", PdfFixtures.embeddedTextPdf("later"))
            rig.session.rescan()
            val soon = rig.docId("soon.pdf")
            val later = rig.docId("later.pdf")
            rig.session.meta.setField(soon, FieldKeys.DOC_DATE, today.plusDays(2).toString())
            rig.session.meta.setField(later, FieldKeys.DOC_DATE, today.plusDays(20).toString())

            val reminders = rig.session.reminders
            reminders.setFieldKeys(listOf(FieldKeys.DOC_DATE))
            reminders.setLeadDays(3)
            reminders.setTimeOfDay(LocalTime(0, 0))
            reminders.setEnabled(true)
            rig.clock.now = ReminderWindow.triggerAt(today, LocalTime(10, 0), zone)

            reminders.claimDueReminders().map { it.docId } shouldContainExactly listOf(soon)
        }

    @Test
    fun aDocumentWithTwoWatchedDatesIsOneReminderAndOneNotification() =
        appSessionTest { rig ->
            val today = ReminderWindow.today(rig.clock.now, zone)
            rig.session.fieldRegistry.upsert(
                FieldDef(
                    "renewal",
                    "Renewal",
                    FieldType.DATE,
                    showByDefault = false,
                    aiEnabled = false,
                    position = FieldDef.PLACE_LAST,
                ),
            )
            rig.writePdf("contract.pdf", PdfFixtures.embeddedTextPdf("contract"))
            rig.session.rescan()
            val doc = rig.docId("contract.pdf")
            rig.session.meta.setField(doc, FieldKeys.DOC_DATE, today.plusDays(1).toString())
            rig.session.meta.setField(doc, "renewal", today.plusDays(5).toString())

            val reminders = rig.session.reminders
            reminders.setFieldKeys(listOf(FieldKeys.DOC_DATE, "renewal"))
            reminders.setEnabled(true)
            reminders.setTimeOfDay(LocalTime(0, 0))
            rig.clock.now = ReminderWindow.triggerAt(today, LocalTime(10, 0), zone)

            reminders
                .observeUpcoming()
                .first()
                .single()
                .dueDate shouldBe today.plusDays(1)
            val claimed = reminders.claimDueReminders()
            claimed.single().docId shouldBe doc
            claimed.single().dueDate shouldBe today.plusDays(1)
        }

    @Test
    fun upcomingKeepsOneRowPerDocumentAndGroupsItByTheEarliestDate() =
        appSessionTest { rig ->
            val today = ReminderWindow.today(rig.clock.now, zone)
            rig.writePdf("overdue.pdf", PdfFixtures.embeddedTextPdf("overdue"))
            rig.writePdf("today.pdf", PdfFixtures.embeddedTextPdf("today"))
            rig.writePdf("later.pdf", PdfFixtures.embeddedTextPdf("later"))
            rig.session.rescan()
            val overdue = rig.docId("overdue.pdf")
            val dueToday = rig.docId("today.pdf")
            val later = rig.docId("later.pdf")
            rig.session.meta.setField(overdue, FieldKeys.DOC_DATE, today.plusDays(-1).toString())
            rig.session.meta.setField(dueToday, FieldKeys.DOC_DATE, today.toString())
            rig.session.meta.setField(later, FieldKeys.DOC_DATE, today.plusDays(12).toString())

            val reminders = rig.session.reminders
            reminders.setFieldKeys(listOf(FieldKeys.DOC_DATE))
            reminders.setEnabled(true)

            val upcoming = reminders.observeUpcoming().first()
            upcoming.map { it.doc.docId } shouldContainExactly listOf(overdue, dueToday, later)
            upcoming.map { it.bucket } shouldContainExactly
                listOf(ReminderBucket.OVERDUE, ReminderBucket.TODAY, ReminderBucket.LATER)
        }
}
