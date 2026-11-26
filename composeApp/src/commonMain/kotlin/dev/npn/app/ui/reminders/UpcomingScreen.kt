package dev.npn.app.ui.reminders

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EventAvailable
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.platform.formatReminderDate
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.LoadingIndicator
import dev.npn.app.ui.common.TooltipIconButton
import dev.npn.app.ui.common.rememberSnackbarMessages
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReminderBucket
import dev.npn.core.app.UpcomingReminder
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.reminders_disabled_subtitle
import no_papers_needed.composeapp.generated.resources.reminders_disabled_title
import no_papers_needed.composeapp.generated.resources.reminders_empty_subtitle
import no_papers_needed.composeapp.generated.resources.reminders_empty_title
import no_papers_needed.composeapp.generated.resources.reminders_group_later
import no_papers_needed.composeapp.generated.resources.reminders_group_overdue
import no_papers_needed.composeapp.generated.resources.reminders_group_soon
import no_papers_needed.composeapp.generated.resources.reminders_group_today
import no_papers_needed.composeapp.generated.resources.reminders_mark_done
import no_papers_needed.composeapp.generated.resources.reminders_no_fields_subtitle
import no_papers_needed.composeapp.generated.resources.reminders_no_fields_title
import no_papers_needed.composeapp.generated.resources.reminders_row_meta
import no_papers_needed.composeapp.generated.resources.reminders_upcoming_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

private val GroupOrder =
    listOf(
        ReminderBucket.OVERDUE,
        ReminderBucket.TODAY,
        ReminderBucket.SOON,
        ReminderBucket.LATER,
    )

/** A group's heading; "soon" is the lead time, which is what notifies ahead. */
@Composable
private fun ReminderBucket.label(leadDays: Int): String =
    when (this) {
        ReminderBucket.OVERDUE -> stringResource(Res.string.reminders_group_overdue)
        ReminderBucket.TODAY -> stringResource(Res.string.reminders_group_today)
        ReminderBucket.SOON -> pluralStringResource(Res.plurals.reminders_group_soon, leadDays, leadDays)
        ReminderBucket.LATER -> stringResource(Res.string.reminders_group_later)
    }

/**
 * The Upcoming list: live documents whose watched DATE fields are still open, grouped Overdue /
 * Today / within the lead time / Later. Rows open the document, and a done one (paid, renewed) is
 * marked so and leaves; there is no second library engine here, the list is the reminder facade's
 * already-grouped read.
 */
@Composable
internal fun UpcomingScreen(
    session: LibrarySession,
    onBack: () -> Unit,
    onOpenDoc: (docId: String) -> Unit,
    vm: UpcomingViewModel = viewModel { UpcomingViewModel(session) },
) {
    val loaded by vm.state.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarMessages(vm.messages)
    Scaffold(
        topBar = { BackTopBar(title = stringResource(Res.string.reminders_upcoming_title), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val state = loaded
        when {
            state == null -> {
                LoadingIndicator(Modifier.padding(padding))
            }

            !state.enabled -> {
                EmptyState(
                    icon = Icons.Outlined.NotificationsOff,
                    title = stringResource(Res.string.reminders_disabled_title),
                    subtitle = stringResource(Res.string.reminders_disabled_subtitle),
                    modifier = Modifier.padding(padding),
                )
            }

            !state.active -> {
                EmptyState(
                    icon = Icons.Outlined.NotificationsOff,
                    title = stringResource(Res.string.reminders_no_fields_title),
                    subtitle = stringResource(Res.string.reminders_no_fields_subtitle),
                    modifier = Modifier.padding(padding),
                )
            }

            state.items.isEmpty() -> {
                EmptyState(
                    icon = Icons.Outlined.EventAvailable,
                    title = stringResource(Res.string.reminders_empty_title),
                    subtitle = stringResource(Res.string.reminders_empty_subtitle),
                    modifier = Modifier.padding(padding),
                )
            }

            else -> {
                UpcomingList(
                    state = state,
                    actions = remember(vm) { object : UpcomingActions by vm {} },
                    onOpenDoc = onOpenDoc,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/** [state]'s items grouped in the reminder window's order, each group only when it has documents. */
@Composable
private fun UpcomingList(
    state: UpcomingUiState,
    actions: UpcomingActions,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize()) {
        item(key = "permission") {
            ReminderPermissionWarning(
                notifies = state.notifiesHere,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        for (bucket in GroupOrder) {
            val group = state.items.filter { it.bucket == bucket }
            if (group.isEmpty()) continue
            item(key = "header-${bucket.name}") {
                Text(
                    text = bucket.label(state.leadDays),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(group, key = { it.doc.docId }) { reminder ->
                UpcomingRow(
                    reminder = reminder,
                    onOpen = { onOpenDoc(reminder.doc.docId) },
                    onDone = { actions.markDone(reminder) },
                )
            }
        }
    }
}

@Composable
private fun UpcomingRow(
    reminder: UpcomingReminder,
    onOpen: () -> Unit,
    onDone: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = reminder.doc.displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text =
                        stringResource(
                            Res.string.reminders_row_meta,
                            reminder.fieldLabel,
                            formatReminderDate(reminder.dueDate),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TooltipIconButton(
                icon = Icons.Outlined.TaskAlt,
                label = stringResource(Res.string.reminders_mark_done),
                onClick = onDone,
            )
        }
        HorizontalDivider()
    }
}
