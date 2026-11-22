package dev.npn.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Keyboard
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.LocalWindowWidthClass
import dev.npn.app.ui.common.TooltipIconButton
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.nav_more
import no_papers_needed.composeapp.generated.resources.nav_more_destinations
import no_papers_needed.composeapp.generated.resources.nav_queue_failed
import no_papers_needed.composeapp.generated.resources.nav_queue_pending
import no_papers_needed.composeapp.generated.resources.nav_queue_review
import no_papers_needed.composeapp.generated.resources.nav_shortcuts
import no_papers_needed.composeapp.generated.resources.palette_title
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Size of the running ring drawn around the Activity icon. */
private val RUNNING_RING_SIZE = 30.dp

/**
 * Adaptive navigation shell around the app's NavHost: a labeled [NavigationRail] beside the
 * content on wide layouts, a [NavigationBar] below it on compact ones. Both are hidden while a
 * non-top-level route (document detail, rules editor) is on top, where the pushed screen owns
 * the full window. Exception: the assistant's screens keep the shell visible (with its item
 * selected), since that is where they are launched from.
 *
 * The two surfaces offer the same destinations, but the compact bar has room for fewer of them at
 * once, so it keeps [TopLevelDestination.compactBar], the assistant and Activity, and reaches the
 * others through a "More" sheet. [queue] drives Activity's indicator: a running ring while work is
 * pending, and a badge when something waits on the user. [onPalette] opens the command palette from
 * the rail or the "More" sheet. [onShortcuts] opens the keyboard shortcut list; null on a platform
 * without one.
 */
@Composable
fun NavigationShell(
    current: TopLevelDestination?,
    assistantCurrent: Boolean,
    aiConfigured: Boolean,
    queue: QueueStatus,
    onNavigate: (TopLevelDestination) -> Unit,
    onAssistant: () -> Unit,
    /** Open the command palette from the rail or the compact bar's "More" sheet. */
    onPalette: () -> Unit,
    onShortcuts: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val items =
        ShellItems(
            current = current,
            assistantShown = aiConfigured || assistantCurrent,
            assistantCurrent = assistantCurrent,
            queue = queue,
            onNavigate = onNavigate,
            onAssistant = onAssistant,
            onPalette = onPalette,
            onShortcuts = onShortcuts,
        )
    val shellShown = current != null || assistantCurrent
    // One movable slot for both layouts: crossing the breakpoint (a resize, a rotation) moves the
    // content into the other layout instead of composing it afresh, so the NavHost and everything
    // it remembers survive.
    val slot = remember(content) { movableContentOf(content) }
    if (LocalWindowWidthClass.current.isWide) {
        Row(modifier.fillMaxSize()) {
            if (shellShown) ShellRail(items)
            Box(Modifier.weight(1f)) { slot() }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { slot() }
            if (shellShown) ShellBottomBar(items)
        }
    }
}

/**
 * What both shell surfaces show and do. The assistant item shows while AI is configured, and stays
 * while one of its screens is open, so it never vanishes from under the screen it leads to.
 */
private class ShellItems(
    val current: TopLevelDestination?,
    val assistantShown: Boolean,
    val assistantCurrent: Boolean,
    val queue: QueueStatus,
    val onNavigate: (TopLevelDestination) -> Unit,
    val onAssistant: () -> Unit,
    val onPalette: () -> Unit,
    val onShortcuts: (() -> Unit)?,
)

@Composable
private fun ShellRail(items: ShellItems) {
    NavigationRail {
        Spacer(Modifier.height(8.dp))
        TopLevelDestination.rail.forEach { dest -> RailItem(dest, items) }
        if (items.assistantShown) {
            val selected = items.assistantCurrent
            NavigationRailItem(
                selected = selected,
                onClick = items.onAssistant,
                icon = { AssistantIcon(selected) },
                label = { Text(stringResource(AssistantDestination.label)) },
            )
        }
        Spacer(Modifier.weight(1f))
        RailItem(TopLevelDestination.ACTIVITY, items)
        TooltipIconButton(
            icon = Icons.AutoMirrored.Outlined.ManageSearch,
            label = stringResource(Res.string.palette_title),
            onClick = items.onPalette,
        )
        items.onShortcuts?.let { open ->
            TooltipIconButton(
                icon = Icons.Outlined.Keyboard,
                label = stringResource(Res.string.nav_shortcuts),
                onClick = open,
            )
        }
        RailItem(TopLevelDestination.SETTINGS, items)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RailItem(
    dest: TopLevelDestination,
    items: ShellItems,
) {
    val selected = dest == items.current
    NavigationRailItem(
        selected = selected,
        onClick = { items.onNavigate(dest) },
        icon = { DestinationIcon(dest, selected, items.queue) },
        label = { Text(stringResource(dest.label)) },
    )
}

/**
 * The compact bottom bar: [TopLevelDestination.compactBar], the assistant when it is shown,
 * Activity, and a "More" item for everything else. Nothing here is abbreviated to make it fit,
 * and nothing is exiled into the settings page to be found.
 */
@Composable
private fun ShellBottomBar(items: ShellItems) {
    var moreOpen by remember { mutableStateOf(false) }
    NavigationBar {
        TopLevelDestination.compactBar.forEach { dest -> BarItem(dest, items) }
        if (items.assistantShown) {
            val selected = items.assistantCurrent
            NavigationBarItem(
                selected = selected,
                onClick = items.onAssistant,
                icon = { AssistantIcon(selected) },
                label = { Text(stringResource(AssistantDestination.label)) },
            )
        }
        BarItem(TopLevelDestination.ACTIVITY, items)
        // "More" stays selected while one of the screens behind it is open, so the bar always
        // shows where you are rather than going blank on the sheet's screens.
        val moreSelected =
            items.current in TopLevelDestination.compactMore || items.current == TopLevelDestination.SETTINGS
        NavigationBarItem(
            selected = moreSelected,
            onClick = { moreOpen = true },
            icon = {
                Icon(
                    imageVector = if (moreSelected) Icons.Filled.MoreHoriz else Icons.Outlined.MoreHoriz,
                    contentDescription = null,
                )
            },
            label = { Text(stringResource(Res.string.nav_more)) },
        )
    }
    if (moreOpen) MoreSheet(items, onDismiss = { moreOpen = false })
}

@Composable
private fun RowScope.BarItem(
    dest: TopLevelDestination,
    items: ShellItems,
) {
    val selected = dest == items.current
    NavigationBarItem(
        selected = selected,
        onClick = { items.onNavigate(dest) },
        icon = { DestinationIcon(dest, selected, items.queue) },
        label = { Text(stringResource(dest.label)) },
    )
}

/**
 * The screens the compact bar cannot show at once, one tap behind its "More" item:
 * [TopLevelDestination.compactMore], then settings, last as on the rail, and the keyboard shortcut
 * list where there is one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MoreSheet(
    items: ShellItems,
    onDismiss: () -> Unit,
) {
    val moreDestinations = stringResource(Res.string.nav_more_destinations)
    val pick = { dest: TopLevelDestination ->
        onDismiss()
        items.onNavigate(dest)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .semantics { contentDescription = moreDestinations },
        ) {
            (TopLevelDestination.compactMore + TopLevelDestination.SETTINGS).forEach { dest ->
                val selected = dest == items.current
                MoreSheetRow(
                    label = stringResource(dest.label),
                    icon = if (selected) dest.selectedIcon else dest.icon,
                    selected = selected,
                    onClick = { pick(dest) },
                )
            }
            MoreSheetRow(
                label = stringResource(Res.string.palette_title),
                icon = Icons.AutoMirrored.Outlined.ManageSearch,
                selected = false,
                onClick = {
                    onDismiss()
                    items.onPalette()
                },
            )
            items.onShortcuts?.let { open ->
                MoreSheetRow(
                    label = stringResource(Res.string.nav_shortcuts),
                    icon = Icons.Outlined.Keyboard,
                    selected = false,
                    onClick = {
                        onDismiss()
                        open()
                    },
                )
            }
        }
    }
}

@Composable
private fun MoreSheetRow(
    label: String,
    icon: ImageVector,
    selected: Boolean,
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
    )
}

@Composable
private fun AssistantIcon(selected: Boolean) {
    Icon(
        imageVector = if (selected) AssistantDestination.selectedIcon else AssistantDestination.icon,
        contentDescription = null,
    )
}

/** A destination's icon; Activity's carries the background work's indicator ([ActivityIndicator]). */
@Composable
private fun DestinationIcon(
    dest: TopLevelDestination,
    selected: Boolean,
    queue: QueueStatus,
) {
    val icon = @Composable { Icon(if (selected) dest.selectedIcon else dest.icon, contentDescription = null) }
    if (dest == TopLevelDestination.ACTIVITY) ActivityIndicator(queue, icon) else icon()
}

/**
 * Activity's icon with what the background work is doing: a ring turning around it while work is
 * [QueueStatus.running], and a badge while something waits on the user, which is the only thing
 * worth the loud colour. A failure comes first, then a finished bulk edit; being busy is not an
 * alert. One box either way, so the icon is not composed afresh when the badge or ring comes and
 * goes.
 *
 * The ring and the bare digits read as nothing out loud, so the icon announces a phrase instead.
 */
@Composable
private fun ActivityIndicator(
    queue: QueueStatus,
    icon: @Composable () -> Unit,
) {
    val spoken = queue.spoken()
    BadgedBox(
        modifier = if (spoken == null) Modifier else Modifier.semantics { contentDescription = spoken },
        badge = { if (queue.failed > 0 || queue.awaitingReview > 0) AttentionBadge(queue) },
    ) {
        Box(contentAlignment = Alignment.Center) {
            icon()
            if (queue.running) {
                CircularProgressIndicator(Modifier.size(RUNNING_RING_SIZE), strokeWidth = 2.dp)
            }
        }
    }
}

/** What [ActivityIndicator] announces, the most pressing first; null while nothing is going on. */
@Composable
private fun QueueStatus.spoken(): String? =
    when {
        failed > 0 -> pluralStringResource(Res.plurals.nav_queue_failed, failed, failed)
        awaitingReview > 0 -> pluralStringResource(Res.plurals.nav_queue_review, awaitingReview, awaitingReview)
        pending > 0 -> pluralStringResource(Res.plurals.nav_queue_pending, pending, pending)
        else -> null
    }

@Composable
private fun AttentionBadge(queue: QueueStatus) {
    val failure = queue.failed > 0
    val scheme = MaterialTheme.colorScheme
    Badge(
        containerColor = if (failure) scheme.error else scheme.tertiaryContainer,
        contentColor = if (failure) scheme.onError else scheme.onTertiaryContainer,
    ) {
        Text((if (failure) queue.failed else queue.awaitingReview).toString())
    }
}
