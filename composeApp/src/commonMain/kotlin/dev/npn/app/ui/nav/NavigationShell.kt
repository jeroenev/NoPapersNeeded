package dev.npn.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.LocalWindowWidthClass
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.nav_more
import no_papers_needed.composeapp.generated.resources.nav_more_destinations
import no_papers_needed.composeapp.generated.resources.nav_queue_pending
import no_papers_needed.composeapp.generated.resources.nav_queue_review
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Adaptive navigation shell around the app's NavHost: a labeled [NavigationRail] beside the
 * content on wide layouts, a [NavigationBar] below it on compact ones. Both are hidden while a
 * non-top-level route (document detail, rules editor) is on top, where the pushed screen owns
 * the full window. Exception: the AI screens keep the shell visible (with the matching item
 * selected), since that is where they are launched from.
 *
 * The two surfaces offer the same destinations, but the compact bar has room for fewer of them at
 * once, so it keeps [TopLevelDestination.compactBar] and reaches the others through a "More"
 * sheet. [queue] badges the queue item and, on compact layouts, the "More" item standing in front
 * of it — background work has to be visible from the bar without spending a permanent slot on a
 * screen that is empty most of the time.
 */
@Composable
fun NavigationShell(
    current: TopLevelDestination?,
    aiCurrent: AiDestination?,
    aiConfigured: Boolean,
    queue: QueueStatus,
    onNavigate: (TopLevelDestination) -> Unit,
    onAiChat: () -> Unit,
    onAiEdit: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val ai =
        AiNavItems(
            shown = aiConfigured || aiCurrent != null,
            current = aiCurrent,
            onChat = onAiChat,
            onEdit = onAiEdit,
        )
    val shellShown = current != null || aiCurrent != null
    // One movable slot for both layouts: crossing the breakpoint (a resize, a rotation) moves the
    // content into the other layout instead of composing it afresh, so the NavHost and everything
    // it remembers survive.
    val slot = remember(content) { movableContentOf(content) }
    if (LocalWindowWidthClass.current.isWide) {
        Row(modifier.fillMaxSize()) {
            if (shellShown) ShellRail(current = current, ai = ai, queue = queue, onNavigate = onNavigate)
            Box(Modifier.weight(1f)) { slot() }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { slot() }
            if (shellShown) ShellBottomBar(current = current, ai = ai, queue = queue, onNavigate = onNavigate)
        }
    }
}

/**
 * The shell's AI items, the assistant and AI edit. They show while AI is configured, and stay while
 * one of their screens is open, so they never vanish from under the screen they lead to.
 */
private data class AiNavItems(
    val shown: Boolean,
    val current: AiDestination?,
    private val onChat: () -> Unit,
    private val onEdit: () -> Unit,
) {
    fun open(dest: AiDestination) =
        when (dest) {
            AiDestination.CHAT -> onChat()
            AiDestination.EDIT -> onEdit()
        }
}

@Composable
private fun ShellRail(
    current: TopLevelDestination?,
    ai: AiNavItems,
    queue: QueueStatus,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    NavigationRail {
        Spacer(Modifier.height(8.dp))
        (TopLevelDestination.compactBar + TopLevelDestination.compactMore).forEach { dest ->
            RailItem(dest = dest, current = current, queue = queue, onNavigate = onNavigate)
        }
        if (ai.shown) {
            AiDestination.entries.forEach { dest ->
                NavigationRailItem(
                    selected = ai.current == dest,
                    onClick = { ai.open(dest) },
                    icon = { Icon(dest.icon, contentDescription = null) },
                    label = { Text(stringResource(dest.label)) },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        RailItem(dest = TopLevelDestination.SETTINGS, current = current, queue = queue, onNavigate = onNavigate)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RailItem(
    dest: TopLevelDestination,
    current: TopLevelDestination?,
    queue: QueueStatus,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    val selected = dest == current
    NavigationRailItem(
        selected = selected,
        onClick = { onNavigate(dest) },
        icon = {
            BadgedIcon(dest.badge(queue)) {
                Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.icon,
                    contentDescription = null,
                )
            }
        },
        label = { Text(stringResource(dest.label)) },
    )
}

/** The queue is the only destination that reports on itself. */
private fun TopLevelDestination.badge(queue: QueueStatus): QueueStatus =
    if (this == TopLevelDestination.QUEUE) queue else QueueStatus.None

/**
 * The compact bottom bar: [TopLevelDestination.compactBar], the AI assistant when it is shown, and
 * a "More" item for everything else.
 *
 * Only the assistant gets a bar slot, because it is the AI screen you open cold; AI edit is
 * usually entered from the library's selection mode with documents in hand, so it rides in the
 * sheet next to the rest. Nothing here is abbreviated to make it fit, and nothing is exiled into
 * the settings page to be found.
 */
@Composable
private fun ShellBottomBar(
    current: TopLevelDestination?,
    ai: AiNavItems,
    queue: QueueStatus,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    NavigationBar {
        TopLevelDestination.compactBar.forEach { dest ->
            val selected = dest == current
            NavigationBarItem(
                selected = selected,
                onClick = { onNavigate(dest) },
                icon = {
                    Icon(
                        imageVector = if (selected) dest.selectedIcon else dest.icon,
                        contentDescription = null,
                    )
                },
                label = { Text(stringResource(dest.label)) },
            )
        }
        if (ai.shown) {
            val chat = AiDestination.CHAT
            NavigationBarItem(
                selected = ai.current == chat,
                onClick = { ai.open(chat) },
                icon = { Icon(chat.icon, contentDescription = null) },
                label = { Text(stringResource(chat.label)) },
            )
        }
        // "More" stays selected while one of the screens behind it is open, so the bar always
        // shows where you are rather than going blank on the sheet's screens.
        val moreSelected =
            current in TopLevelDestination.compactMore ||
                current == TopLevelDestination.SETTINGS ||
                ai.current == AiDestination.EDIT
        NavigationBarItem(
            selected = moreSelected,
            onClick = { moreOpen = true },
            icon = {
                BadgedIcon(queue) {
                    Icon(
                        imageVector =
                            if (moreSelected) Icons.Filled.MoreHoriz else Icons.Outlined.MoreHoriz,
                        contentDescription = null,
                    )
                }
            },
            label = { Text(stringResource(Res.string.nav_more)) },
        )
    }
    if (moreOpen) {
        MoreSheet(
            current = current,
            ai = ai,
            queue = queue,
            onDismiss = { moreOpen = false },
            onNavigate = onNavigate,
        )
    }
}

/**
 * The screens the compact bar cannot show at once, one tap behind its "More" item, in the rail's
 * order: [TopLevelDestination.compactMore], then AI edit beneath them the way the rail puts it
 * beneath the other items, then settings, last as on the rail.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(
    current: TopLevelDestination?,
    ai: AiNavItems,
    queue: QueueStatus,
    onDismiss: () -> Unit,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    val moreDestinations = stringResource(Res.string.nav_more_destinations)
    val pick = { dest: TopLevelDestination ->
        onDismiss()
        onNavigate(dest)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .semantics { contentDescription = moreDestinations },
        ) {
            TopLevelDestination.compactMore.forEach { dest ->
                DestinationRow(dest = dest, current = current, queue = queue, onClick = { pick(dest) })
            }
            if (ai.shown) {
                val edit = AiDestination.EDIT
                MoreSheetRow(
                    label = stringResource(edit.label),
                    icon = edit.icon,
                    selected = ai.current == edit,
                    badge = QueueStatus.None,
                    onClick = {
                        onDismiss()
                        ai.open(edit)
                    },
                )
            }
            val settings = TopLevelDestination.SETTINGS
            DestinationRow(dest = settings, current = current, queue = queue, onClick = { pick(settings) })
        }
    }
}

@Composable
private fun DestinationRow(
    dest: TopLevelDestination,
    current: TopLevelDestination?,
    queue: QueueStatus,
    onClick: () -> Unit,
) {
    val selected = dest == current
    MoreSheetRow(
        label = stringResource(dest.label),
        icon = if (selected) dest.selectedIcon else dest.icon,
        selected = selected,
        badge = dest.badge(queue),
        onClick = onClick,
    )
}

@Composable
private fun MoreSheetRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    badge: QueueStatus,
    onClick: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    role = Role.Tab,
                    onClick = onClick,
                ),
        headlineContent = { Text(label) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = if (badge.isEmpty) null else ({ QueueBadge(badge) }),
    )
}

/**
 * Wraps [content] in a [QueueBadge] for [badge], which stays empty while the queue has nothing to
 * report. One box either way, so the icon is not composed afresh when the badge comes and goes.
 */
@Composable
private fun BadgedIcon(
    badge: QueueStatus,
    content: @Composable () -> Unit,
) {
    BadgedBox(badge = { if (!badge.isEmpty) QueueBadge(badge) }) { content() }
}

/**
 * What the processing queue is holding. A finished AI edit run is waiting on the user and never
 * clears itself, so it takes the badge over work that is merely in flight, and takes the loud
 * colour with it — being busy is not an alert.
 *
 * The bare digits read as meaningless out loud, so the badge announces a phrase instead of its
 * own label.
 */
@Composable
private fun QueueBadge(badge: QueueStatus) {
    val review = badge.awaitingReview > 0
    val count = if (review) badge.awaitingReview else badge.pending
    val spoken =
        if (review) {
            pluralStringResource(Res.plurals.nav_queue_review, count, count)
        } else {
            pluralStringResource(Res.plurals.nav_queue_pending, count, count)
        }
    Badge(
        modifier = Modifier.semantics { contentDescription = spoken },
        containerColor =
            if (review) {
                MaterialTheme.colorScheme.tertiaryContainer
            } else {
                MaterialTheme.colorScheme.secondaryContainer
            },
        contentColor =
            if (review) {
                MaterialTheme.colorScheme.onTertiaryContainer
            } else {
                MaterialTheme.colorScheme.onSecondaryContainer
            },
    ) {
        Text(count.toString())
    }
}
