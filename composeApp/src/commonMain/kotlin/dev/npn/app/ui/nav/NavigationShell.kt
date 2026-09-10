package dev.npn.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.WideLayoutBreakpoint
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_ai_assistant
import no_papers_needed.composeapp.generated.resources.library_ai_edit
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
 * once, so it keeps the [CompactPlacement.BAR] entries and reaches the others through a "More"
 * sheet. [queue] badges the queue item and, on compact layouts, the "More" item standing in front
 * of it — background work has to be visible from the bar without spending a permanent slot on a
 * screen that is empty most of the time.
 *
 * [aiProbe] is a tri-state, not a boolean, so the shell can *reserve* the AI slots while the
 * configuration probe is still running rather than adding them the instant it resolves — otherwise
 * the compact bar would shift sideways under the user's finger the moment the answer lands. While
 * [AiProbe.PROBING] the entries are shown but inert; only [AiProbe.UNAVAILABLE] drops them.
 */
@Composable
fun NavigationShell(
    current: TopLevelDestination?,
    aiCurrent: AiDestination?,
    aiProbe: AiProbe,
    queue: QueueStatus,
    onNavigate: (TopLevelDestination) -> Unit,
    onAiChat: () -> Unit,
    onAiEdit: () -> Unit,
    content: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth > WideLayoutBreakpoint) {
            Row(Modifier.fillMaxSize()) {
                if (current != null || aiCurrent != null) {
                    ShellRail(
                        current = current,
                        aiCurrent = aiCurrent,
                        aiProbe = aiProbe,
                        queue = queue,
                        onNavigate = onNavigate,
                        onAiChat = onAiChat,
                        onAiEdit = onAiEdit,
                    )
                }
                Box(Modifier.weight(1f)) { content() }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) { content() }
                if (current != null || aiCurrent != null) {
                    ShellBottomBar(
                        current = current,
                        aiCurrent = aiCurrent,
                        aiProbe = aiProbe,
                        queue = queue,
                        onNavigate = onNavigate,
                        onAiChat = onAiChat,
                        onAiEdit = onAiEdit,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShellRail(
    current: TopLevelDestination?,
    aiCurrent: AiDestination?,
    aiProbe: AiProbe,
    queue: QueueStatus,
    onNavigate: (TopLevelDestination) -> Unit,
    onAiChat: () -> Unit,
    onAiEdit: () -> Unit,
) {
    NavigationRail {
        Spacer(Modifier.height(8.dp))
        TopLevelDestination.entries.forEach { dest ->
            // Settings is pinned after the spacer at the bottom of the rail.
            if (dest == TopLevelDestination.SETTINGS) return@forEach
            RailItem(
                dest = dest,
                selected = dest == current,
                badge = if (dest == TopLevelDestination.QUEUE) queue else QueueStatus.None,
                onNavigate = onNavigate,
            )
        }
        // Reserve the AI items while the probe runs, and keep them around while one of their
        // screens is open, so the slots never appear or vanish from under the user. While probing
        // they are inert; a resolved-unavailable probe drops them.
        if (aiProbe.reservesSlot || aiCurrent != null) {
            val enabled = aiProbe.isConfigured || aiCurrent != null
            NavigationRailItem(
                selected = aiCurrent == AiDestination.CHAT,
                enabled = enabled,
                onClick = onAiChat,
                icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                label = { Text(stringResource(Res.string.library_ai_assistant)) },
            )
            NavigationRailItem(
                selected = aiCurrent == AiDestination.EDIT,
                enabled = enabled,
                onClick = onAiEdit,
                icon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null) },
                label = { Text(stringResource(Res.string.library_ai_edit)) },
            )
        }
        Spacer(Modifier.weight(1f))
        RailItem(
            dest = TopLevelDestination.SETTINGS,
            selected = current == TopLevelDestination.SETTINGS,
            badge = QueueStatus.None,
            onNavigate = onNavigate,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RailItem(
    dest: TopLevelDestination,
    selected: Boolean,
    badge: QueueStatus,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    NavigationRailItem(
        selected = selected,
        onClick = { onNavigate(dest) },
        icon = {
            BadgedIcon(badge) {
                Icon(
                    imageVector = if (selected) dest.selectedIcon else dest.icon,
                    contentDescription = null,
                )
            }
        },
        label = { Text(stringResource(dest.label)) },
    )
}

/**
 * The compact bottom bar: the [CompactPlacement.BAR] destinations, the AI assistant when it is
 * configured, and a "More" item for everything else.
 *
 * Only the assistant gets a bar slot, because it is the AI screen you open cold; AI edit is
 * usually entered from the library's selection mode with documents in hand, so it rides in the
 * sheet next to the rest. Nothing here is abbreviated to make it fit, and nothing is exiled into
 * the settings page to be found.
 */
@Composable
private fun ShellBottomBar(
    current: TopLevelDestination?,
    aiCurrent: AiDestination?,
    aiProbe: AiProbe,
    queue: QueueStatus,
    onNavigate: (TopLevelDestination) -> Unit,
    onAiChat: () -> Unit,
    onAiEdit: () -> Unit,
) {
    var moreOpen by remember { mutableStateOf(false) }
    // Reserve the assistant slot while the probe runs so the bar does not shift sideways when the
    // answer lands, and keep it present while one of the AI screens is open. While probing it is
    // shown but inert; only a resolved-unavailable probe removes it.
    val aiReserved = aiProbe.reservesSlot || aiCurrent != null
    val aiActive = aiProbe.isConfigured || aiCurrent != null
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
        if (aiReserved) {
            NavigationBarItem(
                selected = aiCurrent == AiDestination.CHAT,
                enabled = aiActive,
                onClick = onAiChat,
                icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                label = { Text(stringResource(Res.string.library_ai_assistant)) },
            )
        }
        // "More" stays selected while one of the screens behind it is open, so the bar always
        // shows where you are rather than going blank on the sheet's screens.
        val moreSelected =
            current in TopLevelDestination.compactMore || aiCurrent == AiDestination.EDIT
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
            rows = moreRows(aiEdit = aiReserved),
            current = current,
            aiCurrent = aiCurrent,
            aiEditEnabled = aiActive,
            queue = queue,
            onDismiss = { moreOpen = false },
            onNavigate = { dest ->
                moreOpen = false
                onNavigate(dest)
            },
            onAiEdit = {
                moreOpen = false
                onAiEdit()
            },
        )
    }
}

/** A row of the compact bar's "More" sheet. */
internal sealed interface MoreRow {
    /** A top-level destination the bar has no slot for. */
    data class Destination(
        val dest: TopLevelDestination,
    ) : MoreRow

    /** The AI edit screen, which is a screen above Library rather than a destination of its own. */
    data object AiEdit : MoreRow
}

/**
 * The sheet's rows, in the rail's order: the overflow destinations, then AI edit beneath them the
 * way the rail puts it beneath the other items, then settings — pinned last here because the rail
 * pins it to the bottom, and because it is the one row nobody is looking for in a hurry.
 *
 * [aiEdit] follows the bar's AI assistant item: both appear once the assistant is configured.
 */
internal fun moreRows(aiEdit: Boolean): List<MoreRow> =
    buildList {
        val settings = TopLevelDestination.SETTINGS
        TopLevelDestination.compactMore
            .filter { it != settings }
            .mapTo(this) { MoreRow.Destination(it) }
        if (aiEdit) add(MoreRow.AiEdit)
        if (settings in TopLevelDestination.compactMore) add(MoreRow.Destination(settings))
    }

/** The screens the compact bar cannot show at once, one tap behind its "More" item. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(
    rows: List<MoreRow>,
    current: TopLevelDestination?,
    aiCurrent: AiDestination?,
    aiEditEnabled: Boolean,
    queue: QueueStatus,
    onDismiss: () -> Unit,
    onNavigate: (TopLevelDestination) -> Unit,
    onAiEdit: () -> Unit,
) {
    val moreDestinations = stringResource(Res.string.nav_more_destinations)
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
            rows.forEach { row ->
                when (row) {
                    is MoreRow.Destination -> {
                        val selected = row.dest == current
                        MoreSheetRow(
                            label = stringResource(row.dest.label),
                            icon = if (selected) row.dest.selectedIcon else row.dest.icon,
                            selected = selected,
                            enabled = true,
                            // The queue is the only screen behind the sheet that reports on itself.
                            badge =
                                if (row.dest == TopLevelDestination.QUEUE) queue else QueueStatus.None,
                            onClick = { onNavigate(row.dest) },
                        )
                    }

                    MoreRow.AiEdit -> {
                        MoreSheetRow(
                            label = stringResource(Res.string.library_ai_edit),
                            icon = Icons.Outlined.AutoFixHigh,
                            selected = aiCurrent == AiDestination.EDIT,
                            enabled = aiEditEnabled,
                            badge = QueueStatus.None,
                            onClick = onAiEdit,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MoreSheetRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean,
    badge: QueueStatus,
    onClick: () -> Unit,
) {
    ListItem(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = selected,
                    enabled = enabled,
                    role = Role.Tab,
                    onClick = onClick,
                ),
        headlineContent = { Text(label) },
        leadingContent = { Icon(imageVector = icon, contentDescription = null) },
        trailingContent = if (badge.isEmpty) null else ({ QueueBadge(badge) }),
    )
}

/**
 * Wraps [content] in a [QueueBadge] for [badge], or renders it bare when the queue has nothing to
 * report.
 */
@Composable
private fun BadgedIcon(
    badge: QueueStatus,
    content: @Composable () -> Unit,
) {
    if (badge.isEmpty) {
        content()
        return
    }
    BadgedBox(badge = { QueueBadge(badge) }) { content() }
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
