package dev.npn.app.ui.reminders

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.app.ui.common.UiMessage
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.app.ui.common.uiText
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReminderSettings
import dev.npn.core.app.UpcomingReminder
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_undo
import no_papers_needed.composeapp.generated.resources.reminders_mark_done_failed
import no_papers_needed.composeapp.generated.resources.reminders_marked_done

/** What the Upcoming screen shows: whether reminders are on, and the documents in the window. */
internal data class UpcomingUiState(
    val enabled: Boolean = false,
    /** On and with at least one watched date; an enabled library with none has its own empty state. */
    val active: Boolean = false,
    /** Whether this device notifies, so a blocked permission is worth a warning here. */
    val notifiesHere: Boolean = true,
    /** The lead time, which names the "soon" group. */
    val leadDays: Int = ReminderSettings.DEFAULT_LEAD_DAYS,
    val items: List<UpcomingReminder> = emptyList(),
)

/** The actions an Upcoming row offers. */
internal interface UpcomingActions {
    /** Mark [reminder] done (paid, renewed): it leaves the list on every device, with an Undo. */
    fun markDone(reminder: UpcomingReminder)
}

internal class UpcomingViewModel(
    private val session: LibrarySession,
) : MessagesViewModel(),
    UpcomingActions {
    override val log: NpnLog get() = session.log

    /** Null until both the settings and the documents have answered, so the screen can show a spinner. */
    val state: StateFlow<UpcomingUiState?> =
        combine(
            session.reminders.settings,
            session.reminders.notifiesHere,
            session.reminders.observeUpcoming(),
        ) { settings, notifiesHere, items ->
            UpcomingUiState(settings.enabled, settings.isActive, notifiesHere, settings.leadDays, items)
        }.stateIn(viewModelScope, WhileSubscribedTimeout, null)

    override fun markDone(reminder: UpcomingReminder) =
        launchReporting(Res.string.reminders_mark_done_failed) {
            val docId = reminder.doc.docId
            session.reminders.markDone(docId, reminder.fieldKey, reminder.dueDate)
            message(
                uiText(Res.string.reminders_marked_done, reminder.doc.displayTitle),
                UiMessage.Action(uiText(Res.string.library_undo)) {
                    launchReporting(Res.string.reminders_mark_done_failed) {
                        session.reminders.markOpen(docId, reminder.fieldKey, reminder.dueDate)
                    }
                },
            )
        }
}
