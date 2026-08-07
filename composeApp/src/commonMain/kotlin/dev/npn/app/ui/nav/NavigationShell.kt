package dev.npn.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.WideLayoutBreakpoint
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.library_ai_assistant
import no_papers_needed.composeapp.generated.resources.library_ai_edit
import org.jetbrains.compose.resources.stringResource

/**
 * Adaptive navigation shell around the app's NavHost: a labeled [NavigationRail] beside the
 * content on wide layouts, a [NavigationBar] below it on compact ones. Both are hidden while a
 * non-top-level route (document detail, rules editor) is on top, where the pushed screen owns
 * the full window. Exception: the AI screens keep the rail visible on wide layouts (with the
 * matching rail item selected), since that is where they are launched from.
 */
@Composable
fun NavigationShell(
    current: TopLevelDestination?,
    aiCurrent: AiDestination?,
    aiConfigured: Boolean,
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
                        aiConfigured = aiConfigured,
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
                if (current != null) ShellBottomBar(current = current, onNavigate = onNavigate)
            }
        }
    }
}

@Composable
private fun ShellRail(
    current: TopLevelDestination?,
    aiCurrent: AiDestination?,
    aiConfigured: Boolean,
    onNavigate: (TopLevelDestination) -> Unit,
    onAiChat: () -> Unit,
    onAiEdit: () -> Unit,
) {
    NavigationRail {
        Spacer(Modifier.height(8.dp))
        TopLevelDestination.entries.forEach { dest ->
            // Settings is pinned after the spacer at the bottom of the rail.
            if (dest == TopLevelDestination.SETTINGS) return@forEach
            RailItem(dest = dest, selected = dest == current, onNavigate = onNavigate)
        }
        // Keep the AI items around while one of their screens is open, even if the configuration
        // probe has not resolved yet, so the selected item never vanishes from under the user.
        if (aiConfigured || aiCurrent != null) {
            NavigationRailItem(
                selected = aiCurrent == AiDestination.CHAT,
                onClick = onAiChat,
                icon = { Icon(Icons.Outlined.AutoAwesome, contentDescription = null) },
                label = { Text(stringResource(Res.string.library_ai_assistant)) },
            )
            NavigationRailItem(
                selected = aiCurrent == AiDestination.EDIT,
                onClick = onAiEdit,
                icon = { Icon(Icons.Outlined.AutoFixHigh, contentDescription = null) },
                label = { Text(stringResource(Res.string.library_ai_edit)) },
            )
        }
        Spacer(Modifier.weight(1f))
        RailItem(
            dest = TopLevelDestination.SETTINGS,
            selected = current == TopLevelDestination.SETTINGS,
            onNavigate = onNavigate,
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun RailItem(
    dest: TopLevelDestination,
    selected: Boolean,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    NavigationRailItem(
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

@Composable
private fun ShellBottomBar(
    current: TopLevelDestination,
    onNavigate: (TopLevelDestination) -> Unit,
) {
    NavigationBar {
        TopLevelDestination.entries.forEach { dest ->
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
    }
}
