package dev.npn.app.ui.reminders

import dev.npn.app.FakeLibrarySession
import dev.npn.app.platform.ReminderDelivery
import dev.npn.app.platform.reminderNotificationTag
import dev.npn.app.platform.reminderNotificationsPermitted
import dev.npn.app.ui.common.OpenDocumentRequest
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReminderBucket
import dev.npn.core.app.ReminderCandidate
import dev.npn.core.app.ReminderWindow
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ReminderReviewTest {
    private val candidates =
        (1..3).map {
            ReminderCandidate("doc$it", "Private title", "due", "Due", LocalDate(2026, 11, 25), ReminderBucket.TODAY)
        }

    @Test
    fun remindersShownTogetherKeepTheirClaims() =
        runTest {
            val session = FakeLibrarySession()
            session.reminders.due = candidates
            val shown = mutableListOf<List<ReminderCandidate>>()
            deliverReminders(session) {
                shown += it
                ReminderDelivery.NOTIFIED
            }
            assertEquals(listOf(candidates), shown)
            assertEquals(emptyList(), session.reminders.released)
        }

    @Test
    fun nothingShownReleasesEveryClaim() =
        runTest {
            val session = FakeLibrarySession()
            session.reminders.due = candidates
            // A desktop with no notification service, or a blocked permission: nothing was shown.
            deliverReminders(session) { ReminderDelivery.NOT_SHOWN }
            assertEquals(candidates, session.reminders.released)
        }

    @Test
    fun aFailedDeliveryReleasesEveryClaim() =
        runTest {
            val session = FakeLibrarySession()
            session.reminders.due = candidates
            deliverReminders(session) { error("The notification service went away") }
            assertEquals(candidates, session.reminders.released)
        }

    @Test
    fun distinctFieldsHaveDistinctNotificationTagsEvenWithTheSameLabel() {
        assertNotEquals(
            reminderNotificationTag("doc", "date-a", "2026-11-23"),
            reminderNotificationTag("doc", "date-b", "2026-11-23"),
        )
        assertNotEquals(
            reminderNotificationTag("ab", "c", "2026-11-23"),
            reminderNotificationTag("a", "bc", "2026-11-23"),
        )
    }

    @Test
    fun disabledChannelBlocksReminderClaims() {
        assertEquals(false, reminderNotificationsPermitted(appEnabled = true, channelBlocked = true))
        assertEquals(false, reminderNotificationsPermitted(appEnabled = false, channelBlocked = false))
        assertEquals(true, reminderNotificationsPermitted(appEnabled = true, channelBlocked = false))
    }

    @Test
    fun notificationRequestIsOnlyAcceptedByItsOriginatingLibrary() {
        val request = OpenDocumentRequest("doc", "library-a")
        assertEquals(true, request.belongsTo("library-a"))
        assertEquals(false, request.belongsTo("library-b"))
        assertEquals(false, request.belongsTo(null))
    }

    @Test
    fun failedInitialRescanDoesNotDeliverOrConsumeClaims() =
        runTest {
            val session = FakeLibrarySession()
            var deliveries = 0
            session.initialRescanSucceeded = false
            deliverAfterRescan(session) { deliveries++ }
            assertEquals(0, deliveries)
            session.initialRescanSucceeded = true
            deliverAfterRescan(session) { deliveries++ }
            assertEquals(1, deliveries)
        }

    // --- The desktop loop's wake-ups ---

    @Test
    fun aWaitThatOnlyBringsTheCheckCloserDoesNotWake() {
        assertNull(reminderWake(before = 3_600_000, after = 3_540_000, zoneChanged = false))
    }

    @Test
    fun eachWakeUpBranchIsTold() {
        assertEquals(ReminderWake.ZONE_CHANGED, reminderWake(3_600_000, 3_540_000, zoneChanged = true))
        assertEquals(ReminderWake.TRIGGER_DUE, reminderWake(RECHECK_MS, 86_400_000, zoneChanged = false))
        assertEquals(ReminderWake.CLOCK_JUMPED, reminderWake(3_600_000, 80_000_000, zoneChanged = false))
    }

    /**
     * The loop over a fake wall clock and zone: the wall clock is the test's virtual time plus
     * [jump], which a test moves to stand for a suspended machine, and the loop's waits are the
     * test's virtual delays.
     */
    private class LoopRig(
        start: LocalDateTime,
        var zone: TimeZone = TimeZone.of("Europe/Brussels"),
    ) {
        private val origin = start.toInstant(zone).toEpochMilliseconds()
        var jump = 0L
        var checks = 0

        fun TestScope.wallClock(): Long = origin + currentTime + jump

        fun TestScope.start() =
            async {
                reminderCheckLoop(
                    untilNext = { ReminderWindow.delayUntilNextTrigger(wallClock(), zone) },
                    zone = { zone },
                    check = { checks++ },
                )
            }
    }

    @Test
    fun theLoopChecksWhenTheMorningTimeComes() =
        runTest {
            val rig = LoopRig(LocalDateTime(2026, 11, 26, 8, 58, 30))
            val loop = with(rig) { start() }
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(0, rig.checks)
            // 09:00 comes 90 seconds after the start.
            advanceTimeBy(31_000)
            runCurrent()
            assertEquals(1, rig.checks)
            // And not again until the next morning.
            advanceTimeBy(60L * 60_000)
            runCurrent()
            assertEquals(1, rig.checks)
            loop.cancel()
        }

    @Test
    fun theLoopChecksWhenTheZoneChanges() =
        runTest {
            val rig = LoopRig(LocalDateTime(2026, 11, 26, 7, 0))
            val loop = with(rig) { start() }
            advanceTimeBy(5 * 60_000)
            runCurrent()
            assertEquals(0, rig.checks)
            // Flying west to east: it is suddenly mid-morning, past the check.
            rig.zone = TimeZone.of("Asia/Tokyo")
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, rig.checks)
            loop.cancel()
        }

    @Test
    fun theLoopChecksWhenTheWallClockJumpsPastTheMorningTime() =
        runTest {
            val rig = LoopRig(LocalDateTime(2026, 11, 26, 8, 0))
            val loop = with(rig) { start() }
            advanceTimeBy(5 * 60_000)
            runCurrent()
            assertEquals(0, rig.checks)
            // The laptop slept from 08:05 to 11:00: the monotonic wait saw one minute of it.
            rig.jump = 3L * 60 * 60_000 - 6 * 60_000
            advanceTimeBy(60_000)
            runCurrent()
            assertEquals(1, rig.checks)
            loop.cancel()
        }

    @Test
    fun theLoopEndsWhenRemindersStop() =
        runTest {
            var next: Long? = 3_600_000
            val loop = async { reminderCheckLoop(untilNext = { next }, zone = { TimeZone.UTC }, check = {}) }
            runCurrent()
            next = null
            advanceTimeBy(RECHECK_MS)
            runCurrent()
            assertEquals(true, loop.isCompleted)
        }

    // --- The Android worker's run ---

    private class RunRig {
        var opened = 0
        var checked = 0
        val planned = mutableListOf<LibrarySession?>()

        suspend fun run(
            attempt: Int,
            permitted: Boolean = true,
            open: suspend () -> LibrarySession? = { FakeLibrarySession() },
            check: suspend (LibrarySession) -> Unit = {},
        ): ReminderRunOutcome =
            runReminderCheck(
                attempt = attempt,
                permitted = { permitted },
                open = {
                    opened++
                    open()
                },
                check = {
                    checked++
                    check(it)
                },
                plan = { planned += it },
                log = NpnLog.None,
            )
    }

    @Test
    fun aBlockedPermissionNeverOpensTheLibrary() =
        runTest {
            val rig = RunRig()
            assertEquals(ReminderRunOutcome.DONE, rig.run(attempt = 0, permitted = false))
            assertEquals(0, rig.opened)
            assertEquals(0, rig.checked)
            // The next morning is still planned, from the fixed time alone.
            assertEquals(listOf<LibrarySession?>(null), rig.planned)
        }

    @Test
    fun aLibraryThatWillNotOpenIsRetriedOnceThenLeftForTomorrow() =
        runTest {
            val rig = RunRig()
            val broken: suspend () -> LibrarySession? = { error("The library folder is gone") }
            assertEquals(ReminderRunOutcome.RETRY, rig.run(attempt = 0, open = broken))
            assertEquals(emptyList(), rig.planned)
            assertEquals(ReminderRunOutcome.DONE, rig.run(attempt = 1, open = broken))
            assertEquals(listOf<LibrarySession?>(null), rig.planned)
            // However high WorkManager's count goes, the run never asks for another retry.
            assertEquals(ReminderRunOutcome.DONE, rig.run(attempt = 7, open = broken))
        }

    @Test
    fun aFailedCheckIsRetriedOnceThenPlansFromTheSession() =
        runTest {
            val rig = RunRig()
            val session = FakeLibrarySession()
            val failing: suspend (LibrarySession) -> Unit = { error("The initial rescan failed") }
            assertEquals(ReminderRunOutcome.RETRY, rig.run(attempt = 0, open = { session }, check = failing))
            assertEquals(ReminderRunOutcome.DONE, rig.run(attempt = 1, open = { session }, check = failing))
            assertEquals(listOf<LibrarySession?>(session), rig.planned)
        }

    @Test
    fun aRunWithNoLibraryEndsWithoutPlanning() =
        runTest {
            val rig = RunRig()
            assertEquals(ReminderRunOutcome.DONE, rig.run(attempt = 0, open = { null }))
            assertEquals(emptyList(), rig.planned)
        }
}
