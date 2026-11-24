package dev.npn.app.platform

import androidx.work.WorkInfo
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * A clock or time-zone change moves only a reminder check that is waiting to run. Without one
 * (reminders off, or never turned on), the worker must not start: it would open and rescan the
 * library in the background for nothing.
 */
class ReminderTimeChangeTest {
    @Test
    fun aWaitingCheckIsMoved() {
        reminderCheckScheduled(listOf(WorkInfo.State.ENQUEUED)) shouldBe true
    }

    @Test
    fun noScheduledCheckStartsNothing() {
        reminderCheckScheduled(emptyList()) shouldBe false
        reminderCheckScheduled(listOf(WorkInfo.State.CANCELLED)) shouldBe false
        reminderCheckScheduled(listOf(WorkInfo.State.SUCCEEDED)) shouldBe false
        reminderCheckScheduled(listOf(WorkInfo.State.FAILED)) shouldBe false
    }

    @Test
    fun aRunningCheckReschedulesItselfAndIsLeftAlone() {
        // The running worker reads the current zone when it schedules its next run.
        reminderCheckScheduled(listOf(WorkInfo.State.RUNNING)) shouldBe false
    }
}
