package dev.npn.core.app

import dev.npn.core.model.FieldDef
import dev.npn.core.model.FieldKeys
import dev.npn.core.model.FieldType
import dev.npn.core.pdf.PdfFixtures
import dev.npn.core.util.EpochClock
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    fun localDateTicksFollowMidnightAndTimeZoneChanges() =
        runTest {
            var now = ReminderWindow.triggerAt(LocalDate(2026, 11, 23), LocalTime(23, 59), TimeZone.UTC)
            var currentZone: TimeZone = TimeZone.UTC
            val ticks = async { localDateTicks(EpochClock { now }) { currentZone }.take(3).toList() }
            runCurrent()
            now += 60_000
            advanceTimeBy(60_000)
            runCurrent()
            currentZone = TimeZone.of("America/Los_Angeles")
            advanceTimeBy(60_000)
            runCurrent()
            ticks.await().map { it.first } shouldContainExactly
                listOf(LocalDate(2026, 11, 23), LocalDate(2026, 11, 24), LocalDate(2026, 11, 23))
        }

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
    fun enablingAfterSelectedFieldWasRemovedFallsBackToDocumentDate() =
        appSessionTest { rig ->
            val reminders = rig.session.reminders
            rig.session.fieldRegistry.upsert(
                FieldDef("renewal", "Renewal", FieldType.DATE, false, false, FieldDef.PLACE_LAST),
            )
            reminders.setFieldKeys(listOf("renewal"))
            rig.session.fieldRegistry.remove("renewal")
            reminders.setEnabled(true)
            reminders.settings.first().fieldKeys shouldContainExactly listOf(FieldKeys.DOC_DATE)
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
            claimed.map { it.docId } shouldContainExactlyInAnyOrder listOf(doc, doc)
            claimed.map { it.dueDate } shouldContainExactlyInAnyOrder
                listOf(today.plusDays(1), today.plusDays(5))
        }

    @Test
    fun overdueDatesOutsideTheLeadWindowDoNotNotifyButStillShowUpcoming() =
        appSessionTest { rig ->
            val today = ReminderWindow.today(rig.clock.now, zone)
            rig.writePdf("old.pdf", PdfFixtures.embeddedTextPdf("old"))
            rig.session.rescan()
            val doc = rig.docId("old.pdf")
            rig.session.meta.setField(doc, FieldKeys.DOC_DATE, today.plusDays(-30).toString())

            val reminders = rig.session.reminders
            reminders.setFieldKeys(listOf(FieldKeys.DOC_DATE))
            reminders.setLeadDays(3)
            reminders.setTimeOfDay(LocalTime(0, 0))
            reminders.setEnabled(true)
            rig.clock.now = ReminderWindow.triggerAt(today, LocalTime(10, 0), zone)

            reminders
                .observeUpcoming()
                .first()
                .single()
                .bucket shouldBe ReminderBucket.OVERDUE
            reminders.claimDueReminders().shouldBeEmpty()
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
