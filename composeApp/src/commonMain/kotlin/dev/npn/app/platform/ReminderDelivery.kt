package dev.npn.app.platform

/** What happened to a due reminder on this platform. */
enum class ReminderDelivery {
    /** The system notified the user (a notification or a tray message). */
    NOTIFIED,

    /** No system notification was available; the in-app Upcoming badge carries it instead. */
    IN_APP,

    /** Notifications are not permitted, so nothing was shown and nothing should be claimed. */
    NOT_PERMITTED,
}

/** Stable, unambiguous Android notification identity, independent of the field label. */
internal fun reminderNotificationTag(
    docId: String,
    fieldKey: String,
    dueDate: String,
): String = "${docId.length}:$docId${fieldKey.length}:$fieldKey$dueDate"

/** A disabled notification channel blocks delivery even when the app itself is permitted. */
internal fun reminderNotificationsPermitted(
    appEnabled: Boolean,
    channelBlocked: Boolean,
): Boolean = appEnabled && !channelBlocked

/** System broadcasts that can move the next local-calendar reminder time. */
internal fun isReminderTimeChange(action: String?): Boolean =
    action == "android.intent.action.TIMEZONE_CHANGED" || action == "android.intent.action.TIME_SET"
