package dev.npn.app.ui.reminders

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import dev.npn.app.platform.ReminderDelivery
import dev.npn.app.platform.canShowReminderNotifications
import dev.npn.app.platform.scheduleReminderChecks
import dev.npn.app.platform.showReminderNotification
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReminderBucket
import dev.npn.core.util.runLogged
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * The in-process reminder check, for as long as the library is open. The next check is scheduled
 * for the configured local time; a check that runs late (the app was closed over it) catches up
 * once, and the device's claims in `device.db` keep it from repeating what the Android worker
 * already showed. Platforms that run a worker (Android) also get [scheduleReminderChecks].
 */
@Composable
internal fun ReminderChecks(
    session: LibrarySession,
    onInAppNotification: () -> Unit,
) {
    val inApp by rememberUpdatedState(onInAppNotification)
    LaunchedEffect(session) {
        session.reminders.settings.collectLatest { settings ->
            val untilNext = session.reminders.nextCheckDelayMillis()
            scheduleReminderChecks(settings.isActive, untilNext ?: 0L)
            if (!settings.isActive) return@collectLatest
            if (!canShowReminderNotifications()) return@collectLatest
            deliver(session, inApp)
            while (true) {
                val next = session.reminders.nextCheckDelayMillis() ?: break
                delay(next.coerceAtLeast(1_000L))
                deliver(session, inApp)
            }
        }
    }
}

private suspend fun deliver(
    session: LibrarySession,
    onInAppNotification: () -> Unit,
) {
    val due =
        session.log
            .runLogged("Reading the reminders due now") {
                session.reminders.claimDueReminders()
            }.orEmpty()
    for (candidate in due) {
        val delivery =
            session.log.runLogged("Showing a reminder for ${candidate.documentTitle}") {
                showReminderNotification(
                    docId = candidate.docId,
                    documentTitle = candidate.documentTitle,
                    fieldLabel = candidate.fieldLabel,
                    dueDate = candidate.dueDate.toString(),
                    overdue = candidate.bucket == ReminderBucket.OVERDUE,
                )
            } ?: continue
        when (delivery) {
            ReminderDelivery.IN_APP -> onInAppNotification()
            ReminderDelivery.NOT_PERMITTED -> return
            ReminderDelivery.NOTIFIED -> Unit
        }
    }
}
