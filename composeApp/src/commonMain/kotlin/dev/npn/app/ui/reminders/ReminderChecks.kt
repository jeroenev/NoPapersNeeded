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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext
import kotlinx.datetime.TimeZone

/**
 * The in-process reminder check, for as long as the library is open. The next check is scheduled
 * for the configured local time; a check that runs late (the app was closed over it) catches up
 * once, and the device's claims in `device.db` keep it from repeating what the Android worker
 * already showed. Platforms that run a worker (Android) also get [scheduleReminderChecks].
 */
@Composable
internal fun ReminderChecks(
    session: LibrarySession,
    libraryLocation: String,
    onInAppNotification: () -> Unit,
) {
    val inApp by rememberUpdatedState(onInAppNotification)
    LaunchedEffect(session, libraryLocation) {
        session.reminders.settings.distinctUntilChanged().collectLatest { settings ->
            val untilNext = session.reminders.nextCheckDelayMillis()
            scheduleReminderChecks(settings.isActive, untilNext ?: 0L)
            if (!settings.isActive) return@collectLatest
            // A permission granted while this session runs must let the catch-up check proceed.
            // Waiting here also covers a second device that syncs reminders on: its user grants
            // the permission from the system prompt and the next check is not lost.
            while (!canShowReminderNotifications()) delay(PERMISSION_RECHECK_MS)
            deliverAfterRescan(session) { deliver(session, libraryLocation, inApp) }
            var zone = TimeZone.currentSystemDefault()
            while (true) {
                val next = session.reminders.nextCheckDelayMillis() ?: break
                val wait = next.coerceIn(1_000L, ZONE_RECHECK_MS)
                delay(wait)
                val currentZone = TimeZone.currentSystemDefault()
                if (currentZone != zone) {
                    zone = currentZone
                    val updated = session.reminders.nextCheckDelayMillis()
                    scheduleReminderChecks(updated != null, updated ?: 0L)
                    deliverAfterRescan(session) { deliver(session, libraryLocation, inApp) }
                } else if (next <= ZONE_RECHECK_MS ||
                    (session.reminders.nextCheckDelayMillis() ?: 0L) > next
                ) {
                    deliverAfterRescan(session) { deliver(session, libraryLocation, inApp) }
                }
            }
        }
    }
}

private suspend fun deliver(
    session: LibrarySession,
    libraryLocation: String,
    onInAppNotification: () -> Unit,
) = withContext(NonCancellable) {
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
                    libraryLocation = libraryLocation,
                    documentTitle = candidate.documentTitle,
                    fieldKey = candidate.fieldKey,
                    fieldLabel = candidate.fieldLabel,
                    dueDate = candidate.dueDate.toString(),
                    overdue = candidate.bucket == ReminderBucket.OVERDUE,
                )
            }
        when (delivery) {
            null -> {
                session.reminders.releaseReminder(candidate)
            }

            ReminderDelivery.IN_APP -> {
                onInAppNotification()
            }

            ReminderDelivery.NOT_PERMITTED -> {
                session.reminders.releaseReminder(candidate)
                return@withContext
            }

            ReminderDelivery.NOTIFIED -> {}
        }
    }
}

/** How often a check waits for the notification permission to be granted. */
private const val PERMISSION_RECHECK_MS = 30_000L
private const val ZONE_RECHECK_MS = 60_000L

/** Do not consume a claim until the index has had a successful initial reconciliation. */
internal suspend fun deliverAfterRescan(
    session: LibrarySession,
    deliver: suspend () -> Unit,
) {
    if (session.awaitInitialRescan()) deliver()
}
