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
import io.kotest.matchers.shouldNotBe
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
            reminders.setEnabled(true)

            val settings = reminders.settings.first()
            settings.enabled shouldBe true
            settings.fieldKeys shouldContainExactly listOf(FieldKeys.DOC_DATE)
            settings.leadDays shouldBe 3

            // The same library read by another device has the same reminder settings.
            val (second, _) = rig.secondSession(backgroundScope)
            second.reminders.settings.first() shouldBe settings
        }

    @Test
    fun enablingWithNoFieldChosenStartsFromTheSeededDueDate() =
        appSessionTest { rig ->
            rig.session.reminders.setEnabled(true)

            val settings =
                rig.session.reminders.settings
                    .first()
            settings.enabled shouldBe true
            settings.fieldKeys shouldContainExactly listOf(FieldKeys.INVOICE_DUE_DATE)
            settings.isActive shouldBe true
        }

    @Test
    fun enablingAfterSelectedFieldWasRemovedFallsBackToDueDate() =
        appSessionTest { rig ->
            val reminders = rig.session.reminders
            rig.session.fieldRegistry.upsert(
                FieldDef("renewal", "Renewal", FieldType.DATE, false, false, FieldDef.PLACE_LAST),
            )
            reminders.setFieldKeys(listOf("renewal"))
            rig.session.fieldRegistry.remove("renewal")
            reminders.setEnabled(true)
            reminders.settings.first().fieldKeys shouldContainExactly listOf(FieldKeys.INVOICE_DUE_DATE)
        }

    @Test
    fun enablingWithoutDueDateKeepsPickerEmptyAndDoesNotNotify() =
        appSessionTest { rig ->
            rig.session.fieldRegistry.remove(FieldKeys.INVOICE_DUE_DATE)
            val reminders = rig.session.reminders
            val today = ReminderWindow.today(rig.clock.now, zone)
            rig.writePdf("dated.pdf", PdfFixtures.embeddedTextPdf("dated"))
            rig.session.rescan()
            rig.session.meta.setField(rig.docId("dated.pdf"), FieldKeys.DOC_DATE, today.toString())
            reminders.setEnabled(true)

            reminders.settings.first().enabled shouldBe true
            reminders.settings
                .first()
                .fieldKeys
                .shouldBeEmpty()
            reminders.observeUpcoming().first().shouldBeEmpty()
            reminders.claimDueReminders().shouldBeEmpty()
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
            reminders.setEnabled(true)
            rig.clock.now = ReminderWindow.triggerAt(today, LocalTime(10, 0), zone)

            reminders.claimDueReminders().map { it.docId } shouldContainExactly listOf(soon)
        }

    @Test
    fun aDocumentWithTwoWatchedDatesIsOneUpcomingRowAndTwoNotifications() =
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

    /** Watch the document date and move the clock past this morning's check. */
    private suspend fun AppSessionRig.watchDocDate(leadDays: Int = ReminderSettings.DEFAULT_LEAD_DAYS): LocalDate {
        val today = ReminderWindow.today(clock.now, zone)
        session.reminders.setFieldKeys(listOf(FieldKeys.DOC_DATE))
        session.reminders.setLeadDays(leadDays)
        session.reminders.setEnabled(true)
        clock.now = ReminderWindow.triggerAt(today, LocalTime(10, 0), zone)
        return today
    }

    @Test
    fun aReminderShownBeforeABakeInPlaceIsNotShownAgainForTheBakedDocument() =
        appSessionTest { rig ->
            rig.writePdf("bill.pdf", PdfFixtures.embeddedTextPdf("water bill"))
            rig.session.rescan()
            val before = rig.docId("bill.pdf")
            val today = rig.watchDocDate()
            rig.session.meta.setField(before, FieldKeys.DOC_DATE, today.plusDays(2).toString())
            rig.session.meta.setField(before, FieldKeys.TITLE, "Water bill")
            val reminders = rig.session.reminders
            reminders.claimDueReminders().map { it.docId } shouldContainExactly listOf(before)

            rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked
            val after = rig.docId("bill.pdf")
            after shouldNotBe before
            reminders.observeUpcoming().first().map { it.doc.docId } shouldContainExactly listOf(after)

            reminders.claimDueReminders().shouldBeEmpty()
            // Not even on a later check, once the claim moved onto the new id.
            reminders.claimDueReminders().shouldBeEmpty()
            // A new date on the baked document is a new reminder.
            rig.session.meta.setField(after, FieldKeys.DOC_DATE, today.plusDays(3).toString())
            reminders.claimDueReminders().map { it.docId } shouldContainExactly listOf(after)
        }

    @Test
    fun aReminderMarkedDoneLeavesUpcomingAndDoesNotNotifyUntilItsDateChanges() =
        appSessionTest { rig ->
            rig.writePdf("paid.pdf", PdfFixtures.embeddedTextPdf("paid"))
            rig.writePdf("open.pdf", PdfFixtures.embeddedTextPdf("open"))
            rig.session.rescan()
            val paid = rig.docId("paid.pdf")
            val open = rig.docId("open.pdf")
            val today = rig.watchDocDate()
            rig.session.meta.setField(paid, FieldKeys.DOC_DATE, today.plusDays(1).toString())
            rig.session.meta.setField(open, FieldKeys.DOC_DATE, today.plusDays(2).toString())
            val reminders = rig.session.reminders

            reminders.markDone(paid, FieldKeys.DOC_DATE, today.plusDays(1))

            reminders.observeUpcoming().first().map { it.doc.docId } shouldContainExactly listOf(open)
            reminders.claimDueReminders().map { it.docId } shouldContainExactly listOf(open)
            // Done is library data: another device sees it too.
            val (second, _) = rig.secondSession(backgroundScope, clock = rig.clock)
            second.rescan()
            second.reminders
                .observeUpcoming()
                .first()
                .map { it.doc.docId } shouldContainExactly listOf(open)

            // Undo brings it back.
            reminders.markOpen(paid, FieldKeys.DOC_DATE, today.plusDays(1))
            reminders.observeUpcoming().first().map { it.doc.docId } shouldContainExactly listOf(paid, open)

            // Done for one date is not done for the next: a renewed date is due again.
            reminders.markDone(paid, FieldKeys.DOC_DATE, today.plusDays(1))
            rig.session.meta.setField(paid, FieldKeys.DOC_DATE, today.plusDays(3).toString())
            reminders.observeUpcoming().first().map { it.doc.docId } shouldContainExactly listOf(open, paid)
        }

    @Test
    fun aDoneMarkFollowsTheDocumentThroughABakeInPlace() =
        appSessionTest { rig ->
            rig.writePdf("bill.pdf", PdfFixtures.embeddedTextPdf("bill"))
            rig.session.rescan()
            val before = rig.docId("bill.pdf")
            val today = rig.watchDocDate()
            rig.session.meta.setField(before, FieldKeys.DOC_DATE, today.toString())
            rig.session.reminders.markDone(before, FieldKeys.DOC_DATE, today)

            rig.session.content.bakeInPlace(before) shouldBe BakeOutcome.Baked

            rig.session.reminders
                .observeUpcoming()
                .first()
                .shouldBeEmpty()
            rig.session.reminders
                .claimDueReminders()
                .shouldBeEmpty()
        }

    @Test
    fun manyOldOverdueDatesDoNotCountAsDue() =
        appSessionTest { rig ->
            val names = (1..12).map { "old-$it.pdf" }
            names.forEach { rig.writePdf(it, PdfFixtures.embeddedTextPdf(it)) }
            rig.writePdf("recent.pdf", PdfFixtures.embeddedTextPdf("recent"))
            rig.session.rescan()
            val today = rig.watchDocDate()
            names.forEachIndexed { index, name ->
                val due = today.plusDays(-40 - 30 * index)
                rig.session.meta.setField(rig.docId(name), FieldKeys.DOC_DATE, due.toString())
            }
            val recent = rig.docId("recent.pdf")
            rig.session.meta.setField(recent, FieldKeys.DOC_DATE, today.plusDays(-3).toString())

            val upcoming =
                rig.session.reminders
                    .observeUpcoming()
                    .first()
            upcoming.map { it.doc.docId } shouldContainExactly listOf(recent)
            upcoming.count { it.bucket == ReminderBucket.OVERDUE || it.bucket == ReminderBucket.TODAY } shouldBe 1
        }

    @Test
    fun aStaleOverdueDateDoesNotHideTheDocumentsNextDate() =
        appSessionTest { rig ->
            rig.session.fieldRegistry.upsert(
                FieldDef("renewal", "Renewal", FieldType.DATE, false, false, FieldDef.PLACE_LAST),
            )
            rig.writePdf("policy.pdf", PdfFixtures.embeddedTextPdf("policy"))
            rig.session.rescan()
            val doc = rig.docId("policy.pdf")
            val today = ReminderWindow.today(rig.clock.now, zone)
            rig.session.meta.setField(doc, FieldKeys.DOC_DATE, today.plusDays(-400).toString())
            rig.session.meta.setField(doc, "renewal", today.plusDays(4).toString())
            val reminders = rig.session.reminders
            reminders.setFieldKeys(listOf(FieldKeys.DOC_DATE, "renewal"))
            reminders.setEnabled(true)

            val row = reminders.observeUpcoming().first().single()
            row.fieldKey shouldBe "renewal"
            row.bucket shouldBe ReminderBucket.SOON

            // Done on the nearer date shows the later one.
            rig.session.meta.setField(doc, FieldKeys.DOC_DATE, today.plusDays(1).toString())
            reminders.markDone(doc, FieldKeys.DOC_DATE, today.plusDays(1))
            reminders
                .observeUpcoming()
                .first()
                .single()
                .fieldKey shouldBe "renewal"
        }

    @Test
    fun aDeviceThatDoesNotNotifyClaimsNothingWhileAnotherStillDoes() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val doc = rig.docId("a.pdf")
            val today = rig.watchDocDate()
            rig.session.meta.setField(doc, FieldKeys.DOC_DATE, today.plusDays(1).toString())
            val reminders = rig.session.reminders
            reminders.notifiesHere.first() shouldBe true

            reminders.setNotifiesHere(false)

            reminders.notifiesHere.first() shouldBe false
            reminders.nextCheckDelayMillis() shouldBe null
            reminders.claimDueReminders().shouldBeEmpty()
            // The list is still there: only the notifications are this device's choice.
            reminders.observeUpcoming().first().map { it.doc.docId } shouldContainExactly listOf(doc)
            val (second, _) = rig.secondSession(backgroundScope, clock = rig.clock)
            second.reminders.notifiesHere.first() shouldBe true

            reminders.setNotifiesHere(true)
            reminders.claimDueReminders().map { it.docId } shouldContainExactly listOf(doc)
        }

    @Test
    fun theUpcomingBucketsFollowTheLeadTime() =
        appSessionTest { rig ->
            rig.writePdf("a.pdf", PdfFixtures.embeddedTextPdf("alpha"))
            rig.session.rescan()
            val doc = rig.docId("a.pdf")
            val today = rig.watchDocDate(leadDays = 14)
            rig.session.meta.setField(doc, FieldKeys.DOC_DATE, today.plusDays(10).toString())

            rig.session.reminders
                .observeUpcoming()
                .first()
                .single()
                .bucket shouldBe ReminderBucket.SOON
            rig.session.reminders
                .claimDueReminders()
                .single()
                .bucket shouldBe ReminderBucket.SOON
        }
}
