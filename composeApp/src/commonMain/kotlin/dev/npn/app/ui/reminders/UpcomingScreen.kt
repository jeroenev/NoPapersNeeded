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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.EmptyState
import dev.npn.app.ui.common.LoadingIndicator
import dev.npn.core.app.LibrarySession
import dev.npn.core.app.ReminderBucket
import dev.npn.core.app.UpcomingReminder
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.reminders_disabled_subtitle
import no_papers_needed.composeapp.generated.resources.reminders_disabled_title
import no_papers_needed.composeapp.generated.resources.reminders_empty_subtitle
import no_papers_needed.composeapp.generated.resources.reminders_empty_title
import no_papers_needed.composeapp.generated.resources.reminders_group_later
import no_papers_needed.composeapp.generated.resources.reminders_group_next_7_days
import no_papers_needed.composeapp.generated.resources.reminders_group_overdue
import no_papers_needed.composeapp.generated.resources.reminders_group_today
import no_papers_needed.composeapp.generated.resources.reminders_row_meta
import no_papers_needed.composeapp.generated.resources.reminders_upcoming_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private val GroupOrder =
    listOf(
        ReminderBucket.OVERDUE,
        ReminderBucket.TODAY,
        ReminderBucket.NEXT_7_DAYS,
        ReminderBucket.LATER,
    )

private val ReminderBucket.label: StringResource
    get() =
        when (this) {
            ReminderBucket.OVERDUE -> Res.string.reminders_group_overdue
            ReminderBucket.TODAY -> Res.string.reminders_group_today
            ReminderBucket.NEXT_7_DAYS -> Res.string.reminders_group_next_7_days
            ReminderBucket.LATER -> Res.string.reminders_group_later
        }

/**
 * The Upcoming list: live documents whose watched DATE fields fall in the reminder window, grouped
 * Overdue / Today / Next 7 days / Later. Rows open the document; there is no second library engine
 * here, the list is the reminder facade's already-grouped read.
 */
@Composable
internal fun UpcomingScreen(
    session: LibrarySession,
    onBack: () -> Unit,
    onOpenDoc: (docId: String) -> Unit,
    vm: UpcomingViewModel = viewModel { UpcomingViewModel(session) },
) {
    val loaded by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { BackTopBar(title = stringResource(Res.string.reminders_upcoming_title), onBack = onBack) },
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
                    items = state.items,
                    onOpenDoc = onOpenDoc,
                    modifier = Modifier.padding(padding),
                )
            }
        }
    }
}

/** [items] grouped in the reminder window's order, each group only when it has documents. */
@Composable
private fun UpcomingList(
    items: List<UpcomingReminder>,
    onOpenDoc: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize()) {
        for (bucket in GroupOrder) {
            val group = items.filter { it.bucket == bucket }
            if (group.isEmpty()) continue
            item(key = "header-${bucket.name}") {
                Text(
                    text = stringResource(bucket.label),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(group, key = { it.doc.docId }) { reminder ->
                UpcomingRow(reminder = reminder, onOpen = { onOpenDoc(reminder.doc.docId) })
            }
        }
    }
}

@Composable
private fun UpcomingRow(
    reminder: UpcomingReminder,
    onOpen: () -> Unit,
) {
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
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
                            reminder.dueDate.toString(),
                        ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalDivider()
    }
}
