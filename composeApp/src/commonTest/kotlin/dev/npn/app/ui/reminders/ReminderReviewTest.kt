package dev.npn.app.ui.reminders

import dev.npn.app.FakeLibrarySession
import dev.npn.app.platform.isReminderTimeChange
import dev.npn.app.platform.reminderNotificationTag
import dev.npn.app.platform.reminderNotificationsPermitted
import dev.npn.app.ui.common.OpenDocumentRequest
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class ReminderReviewTest {
    @Test
    fun clockAndZoneBroadcastsRescheduleReminderWork() {
        assertEquals(true, isReminderTimeChange("android.intent.action.TIMEZONE_CHANGED"))
        assertEquals(true, isReminderTimeChange("android.intent.action.TIME_SET"))
        assertEquals(false, isReminderTimeChange("other"))
    }

    @Test
    fun disabledChannelBlocksReminderClaims() {
        assertEquals(false, reminderNotificationsPermitted(appEnabled = true, channelBlocked = true))
        assertEquals(false, reminderNotificationsPermitted(appEnabled = false, channelBlocked = false))
        assertEquals(true, reminderNotificationsPermitted(appEnabled = true, channelBlocked = false))
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
    fun notificationRequestIsOnlyAcceptedByItsOriginatingLibrary() {
        val request = OpenDocumentRequest("doc", "library-a")
        assertEquals(true, request.belongsTo("library-a"))
        assertEquals(false, request.belongsTo("library-b"))
        assertEquals(false, request.belongsTo(null))
    }

    @Test
    fun grantingPermissionResumesThePendingCatchUpCheck() =
        runTest {
            var permitted = false
            val pending = async { awaitReminderPermission({ permitted }) { delay(30_000) } }
            runCurrent()
            assertEquals(false, pending.isCompleted)
            permitted = true
            advanceTimeBy(30_000)
            runCurrent()
            assertEquals(true, pending.isCompleted)
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
}
