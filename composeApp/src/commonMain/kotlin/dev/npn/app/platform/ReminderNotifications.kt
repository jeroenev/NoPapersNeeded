package dev.npn.app.platform

import androidx.compose.runtime.Composable

/**
 * Whether this device can show a reminder now. Checked before a check claims reminders in
 * `device.db`, so a reminder blocked by a permission is not recorded as shown.
 */
expect fun canShowReminderNotifications(): Boolean

/**
 * Show one due reminder. [documentTitle] and [dueDate] are the user's own values; the sentence
 * around them is a Compose resource resolved here, where a notification is not in a composition.
 * Tapping it opens [docId].
 */
expect suspend fun showReminderNotification(
    docId: String,
    libraryLocation: String,
    documentTitle: String,
    fieldKey: String,
    fieldLabel: String,
    dueDate: String,
    overdue: Boolean,
): ReminderDelivery

/**
 * Schedule this platform's daily reminder check, or cancel it. [delayMillis] is the delay to the
 * next configured local time; platforms that only check while the app runs ignore both.
 */
expect fun scheduleReminderChecks(
    active: Boolean,
    delayMillis: Long,
)

/**
 * Ask for the platform's notification permission, where one is needed (Android 13+); null when
 * there is nothing to ask for. Called when the user turns reminders on.
 */
@Composable
expect fun rememberReminderPermissionRequest(): (() -> Unit)?
