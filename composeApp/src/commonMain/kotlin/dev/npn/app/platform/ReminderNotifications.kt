package dev.npn.app.platform

import androidx.compose.runtime.Composable
import dev.npn.core.app.ReminderCandidate
import kotlinx.datetime.LocalDate

/**
 * Whether this device can show a reminder now: Android's notification permission and channel. Read
 * where reminders are shown and switched on, to warn while they are blocked, and by the Android
 * worker before it opens the library at all.
 */
expect fun canShowReminderNotifications(): Boolean

/**
 * Show the reminders a check claimed, together: one notification for one, a group under one summary
 * for several. [texts] says what they read, generic throughout when the app lock hides the library.
 * Tapping one opens its document, tapping the summary the library's Upcoming list.
 * [ReminderDelivery.NOT_SHOWN] when nothing could be shown, so the claims are released.
 */
expect suspend fun showReminderNotifications(
    libraryLocation: String,
    reminders: List<ReminderCandidate>,
    texts: ReminderTexts,
): ReminderDelivery

/**
 * Schedule this platform's daily reminder check, or cancel it. [delayMillis] is the delay to the
 * next check; platforms that only check while the app runs ([remindersCheckedInApp]) ignore both.
 */
expect fun scheduleReminderChecks(
    active: Boolean,
    delayMillis: Long,
)

/**
 * Whether the open app runs the reminder check itself (desktop). Android leaves it to WorkManager
 * alone, which runs whether or not the app is open, so one check never races another.
 */
expect val remindersCheckedInApp: Boolean

/**
 * What lets notifications through again where the platform blocks them: Android 13+'s permission
 * prompt while it can still be shown, else the app's notification settings. Null where there is
 * nothing to open (desktop).
 */
@Composable
expect fun rememberNotificationSettingsAction(): (() -> Unit)?

/** [date] as this locale writes a date, for a notification and the Upcoming list. */
expect fun formatReminderDate(date: LocalDate): String
