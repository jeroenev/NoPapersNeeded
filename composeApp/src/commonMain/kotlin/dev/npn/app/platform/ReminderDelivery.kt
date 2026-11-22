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
