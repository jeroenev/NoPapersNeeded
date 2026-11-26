package dev.npn.app.platform

import dev.npn.core.app.ReminderBucket
import dev.npn.core.app.ReminderCandidate
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.reminders_notification_body
import no_papers_needed.composeapp.generated.resources.reminders_notification_hidden_body
import no_papers_needed.composeapp.generated.resources.reminders_notification_hidden_title
import no_papers_needed.composeapp.generated.resources.reminders_notification_overdue
import no_papers_needed.composeapp.generated.resources.reminders_notification_summary
import no_papers_needed.composeapp.generated.resources.reminders_notification_title
import org.jetbrains.compose.resources.getPluralString
import org.jetbrains.compose.resources.getString

/** What happened to the due reminders a check handed this platform. */
enum class ReminderDelivery {
    /** The system notified the user. */
    NOTIFIED,

    /**
     * Nothing was shown: notifications are not permitted, or the desktop has no notification
     * service. The claims are released, so a later check tries again; Upcoming still lists them.
     */
    NOT_SHOWN,
}

/** What a reminder notification says: [title] above [body]. */
data class ReminderText(
    val title: String,
    val body: String,
)

/**
 * The words of reminder notifications, resolved where a notification is not in a composition. With
 * [hideDetails] (the app lock is on) neither the field nor the document is named, in the notification
 * or in its lock-screen version: only that something is due.
 */
class ReminderTexts(
    val hideDetails: Boolean,
    private val formatDate: (kotlinx.datetime.LocalDate) -> String = ::formatReminderDate,
) {
    /** What one reminder says, as it shows when the screen is unlocked. */
    suspend fun of(reminder: ReminderCandidate): ReminderText =
        if (hideDetails) {
            generic(1)
        } else {
            ReminderText(
                title = getString(Res.string.reminders_notification_title, reminder.fieldLabel),
                body =
                    getString(
                        if (reminder.bucket == ReminderBucket.OVERDUE) {
                            Res.string.reminders_notification_overdue
                        } else {
                            Res.string.reminders_notification_body
                        },
                        reminder.documentTitle,
                        formatDate(reminder.dueDate),
                    ),
            )
        }

    /**
     * The summary over several [reminders]: its title counts them, its body is one line per
     * reminder, [of]'s body.
     */
    suspend fun summary(reminders: List<ReminderCandidate>): ReminderText =
        if (hideDetails) {
            generic(reminders.size)
        } else {
            ReminderText(
                title = getPluralString(Res.plurals.reminders_notification_summary, reminders.size, reminders.size),
                body = reminders.map { of(it).body }.joinToString("\n"),
            )
        }

    /** What shows on a lock screen, and everywhere while the app lock is on: nothing of the library. */
    suspend fun generic(count: Int): ReminderText =
        ReminderText(
            title =
                if (count == 1) {
                    getString(Res.string.reminders_notification_hidden_title)
                } else {
                    getPluralString(Res.plurals.reminders_notification_summary, count, count)
                },
            body = getPluralString(Res.plurals.reminders_notification_hidden_body, count),
        )
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
