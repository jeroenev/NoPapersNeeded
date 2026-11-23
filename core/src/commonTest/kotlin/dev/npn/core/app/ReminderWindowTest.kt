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
    fun theWindowIsOverdueThenTodayThenSevenDaysThenLater() {
        ReminderWindow.bucket(today.minusDays(1), today) shouldBe ReminderBucket.OVERDUE
        ReminderWindow.bucket(today, today) shouldBe ReminderBucket.TODAY
        ReminderWindow.bucket(today.plusDays(1), today) shouldBe ReminderBucket.NEXT_7_DAYS
        ReminderWindow.bucket(today.plusDays(7), today) shouldBe ReminderBucket.NEXT_7_DAYS
        ReminderWindow.bucket(today.plusDays(8), today) shouldBe ReminderBucket.LATER
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
        val nine = LocalTime(9, 0)

        ReminderWindow.delayUntilNextTrigger(morning, zone, nine) shouldBe 60L * 60L * 1000L
        ReminderWindow.delayUntilNextTrigger(after, zone, nine) shouldBe 23L * 60L * 60L * 1000L
    }

    @Test
    fun theNextCheckKeepsLocalTimeAcrossAutumnClockChange() {
        val saturday = LocalDateTime(2026, 10, 24, 9, 0).toInstant(zone).toEpochMilliseconds()
        ReminderWindow.delayUntilNextTrigger(saturday, zone, LocalTime(9, 0)) shouldBe 25L * 60L * 60L * 1000L
    }

    @Test
    fun aCheckBeforeTheConfiguredTimeStaysQuiet() {
        val morning = LocalDateTime(2026, 11, 22, 8, 59).toInstant(zone).toEpochMilliseconds()
        val nine = LocalDateTime(2026, 11, 22, 9, 0).toInstant(zone).toEpochMilliseconds()
        ReminderWindow.checkAllowed(morning, zone, LocalTime(9, 0)) shouldBe false
        ReminderWindow.checkAllowed(nine, zone, LocalTime(9, 0)) shouldBe true
    }

    @Test
    fun leadDaysBoundsTheNotificationWindow() {
        ReminderWindow.windowEnd(today, 0) shouldBe today
        ReminderWindow.windowEnd(today, 3) shouldBe today.plusDays(3)
        ReminderWindow.windowEnd(today, 10_000) shouldBe today.plusDays(ReminderSettings.LEAD_DAY_RANGE.last)
    }

    @Test
    fun theStoredTimeRoundTrips() {
        formatReminderTime(LocalTime(8, 5)) shouldBe "08:05"
        parseReminderTime("08:05") shouldBe LocalTime(8, 5)
        parseReminderTime("8:5") shouldBe LocalTime(8, 5)
        parseReminderTime("24:00") shouldBe null
        parseReminderTime("09:60") shouldBe null
        parseReminderTime("09:00:00") shouldBe null
    }
}
