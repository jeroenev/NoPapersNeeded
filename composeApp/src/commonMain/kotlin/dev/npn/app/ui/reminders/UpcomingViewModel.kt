package dev.npn.app.ui.reminders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.WhileSubscribedTimeout
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.UpcomingReminder
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** What the Upcoming screen shows: whether reminders are on, and the documents in the window. */
internal data class UpcomingUiState(
    val enabled: Boolean = false,
    /** On and with at least one watched date; an enabled library with none has its own empty state. */
    val active: Boolean = false,
    val items: List<UpcomingReminder> = emptyList(),
)

internal class UpcomingViewModel(
    session: LibrarySession,
) : ViewModel() {
    /** Null until both the settings and the documents have answered, so the screen can show a spinner. */
    val state: StateFlow<UpcomingUiState?> =
        combine(session.reminders.settings, session.reminders.observeUpcoming()) { settings, items ->
            UpcomingUiState(settings.enabled, settings.isActive, items)
        }.stateIn(viewModelScope, WhileSubscribedTimeout, null)
}
