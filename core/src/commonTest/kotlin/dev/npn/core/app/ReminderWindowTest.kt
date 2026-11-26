package dev.npn.core.app

import io.kotest.matchers.shouldBe
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlin.test.Test

private fun LocalDate.plusDays(days: Int): LocalDate = plus(DatePeriod(days = days))

private fun LocalDate.minusDays(days: Int): LocalDate = plus(DatePeriod(days = -days))

class ReminderWindowTest {
    private val zone = TimeZone.of("Europe/Brussels")
    private val today = LocalDate(2026, 11, 22)

    @Test
    fun theWindowIsOverdueThenTodayThenTheLeadTimeThenLater() {
        ReminderWindow.bucket(today.minusDays(1), today, 7) shouldBe ReminderBucket.OVERDUE
        ReminderWindow.bucket(today, today, 7) shouldBe ReminderBucket.TODAY
        ReminderWindow.bucket(today.plusDays(1), today, 7) shouldBe ReminderBucket.SOON
        ReminderWindow.bucket(today.plusDays(7), today, 7) shouldBe ReminderBucket.SOON
        ReminderWindow.bucket(today.plusDays(8), today, 7) shouldBe ReminderBucket.LATER
    }

    @Test
    fun theSoonBucketIsExactlyWhatTheLeadTimeNotifiesAbout() {
        // A 14-day lead notifies about a date 10 days out, so it is not "later".
        ReminderWindow.bucket(today.plusDays(10), today, 14) shouldBe ReminderBucket.SOON
        ReminderWindow.bucket(today.plusDays(15), today, 14) shouldBe ReminderBucket.LATER
        // A 2-day lead does not notify about a date 5 days out.
        ReminderWindow.bucket(today.plusDays(5), today, 2) shouldBe ReminderBucket.LATER
        // No lead time: nothing is notified ahead, so nothing is "soon".
        ReminderWindow.bucket(today.plusDays(1), today, 0) shouldBe ReminderBucket.LATER
        for (lead in listOf(0, 1, 3, 7, 30)) {
            val end = ReminderWindow.windowEnd(today, lead)
            if (lead > 0) ReminderWindow.bucket(end, today, lead) shouldBe ReminderBucket.SOON
            ReminderWindow.bucket(end.plusDays(1), today, lead) shouldBe ReminderBucket.LATER
        }
    }

    @Test
    fun upcomingKeepsOverdueDatesForAMonthOrAsFarAsTheLeadTimeReaches() {
        ReminderWindow.overdueStart(today, 7) shouldBe today.minusDays(ReminderWindow.OVERDUE_DAYS)
        ReminderWindow.overdueStart(today, 60) shouldBe today.minusDays(60)
    }

    @Test
    fun aDueDateIsJudgedOnTheLocalCalendarDayNotUtc() {
        // 23:30 UTC on the 22nd is already the 23rd in Brussels (UTC+1 in November).
        val utcLateEvening = LocalDateTime(2026, 11, 22, 23, 30).toInstant(TimeZone.UTC).toEpochMilliseconds()
        ReminderWindow.today(utcLateEvening, TimeZone.UTC) shouldBe LocalDate(2026, 11, 22)
        ReminderWindow.today(utcLateEvening, zone) shouldBe LocalDate(2026, 11, 23)
    }

    @Test
    fun theNextCheckIsTodaysTimeOrTomorrows() {
        val morning = LocalDateTime(2026, 11, 22, 8, 0).toInstant(zone).toEpochMilliseconds()
        val after = LocalDateTime(2026, 11, 22, 10, 0).toInstant(zone).toEpochMilliseconds()
        ReminderWindow.CHECK_TIME shouldBe LocalTime(9, 0)

        ReminderWindow.delayUntilNextTrigger(morning, zone) shouldBe 60L * 60L * 1000L
        ReminderWindow.delayUntilNextTrigger(after, zone) shouldBe 23L * 60L * 60L * 1000L
    }

    @Test
    fun theNextCheckKeepsLocalTimeAcrossAutumnClockChange() {
        val saturday = LocalDateTime(2026, 10, 24, 9, 0).toInstant(zone).toEpochMilliseconds()
        ReminderWindow.delayUntilNextTrigger(saturday, zone) shouldBe 25L * 60L * 60L * 1000L
    }

    @Test
    fun aCheckBeforeTheMorningTimeStaysQuiet() {
        val morning = LocalDateTime(2026, 11, 22, 8, 59).toInstant(zone).toEpochMilliseconds()
        val nine = LocalDateTime(2026, 11, 22, 9, 0).toInstant(zone).toEpochMilliseconds()
        ReminderWindow.checkAllowed(morning, zone) shouldBe false
        ReminderWindow.checkAllowed(nine, zone) shouldBe true
    }

    @Test
    fun leadDaysBoundsTheNotificationWindow() {
        ReminderWindow.windowEnd(today, 0) shouldBe today
        ReminderWindow.windowEnd(today, 3) shouldBe today.plusDays(3)
        ReminderWindow.windowEnd(today, 10_000) shouldBe today.plusDays(ReminderSettings.LEAD_DAY_RANGE.last)
    }
}
