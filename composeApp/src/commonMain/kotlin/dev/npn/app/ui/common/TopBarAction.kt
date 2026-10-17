package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One icon action a top bar can offer. The same action renders as a tooltip'd icon when it sits
 * directly in the bar and as an icon-plus-label row when it spills into the overflow menu, so the
 * label has to read well in both places.
 */
data class TopBarAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

/**
 * Renders a top bar's actions: [inline] as direct icon buttons, then [overflow] behind a trailing
 * "more" menu whose items keep their icons. Callers split the two lists by how much room the bar
 * has, so a desktop window shows more actions at a glance while a narrow one keeps the overflow.
 *
 * The menu button is only drawn when [overflow] is non-empty, which lets a caller pass an empty
 * overflow to get a plain row of icons.
 */
@Composable
fun TopBarActions(
    inline: List<TopBarAction>,
    overflow: List<TopBarAction> = emptyList(),
    overflowLabel: String,
) {
    inline.forEach { action ->
        TooltipIconButton(
            icon = action.icon,
            label = action.label,
            onClick = action.onClick,
            enabled = action.enabled,
        )
    }
    if (overflow.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Box {
        TooltipIconButton(
            icon = Icons.Filled.MoreVert,
            label = overflowLabel,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            ActionMenuItems(overflow, onDismiss = { expanded = false })
        }
    }
}

/**
 * Every one of [actions] as a direct icon on a [wide] bar, or all of them behind the overflow menu
 * on a compact one.
 */
@Composable
fun TopBarActions(
    actions: List<TopBarAction>,
    wide: Boolean,
    overflowLabel: String,
) {
    if (wide) {
        TopBarActions(inline = actions, overflowLabel = overflowLabel)
    } else {
        TopBarActions(inline = emptyList(), overflow = actions, overflowLabel = overflowLabel)
    }
}

/**
 * [actions] as the icon-plus-label rows of an open menu. Each closes the menu through [onDismiss]
 * before it acts, so an action that opens a dialog never leaves the menu standing behind it.
 */
@Composable
fun ActionMenuItems(
    actions: List<TopBarAction>,
    onDismiss: () -> Unit,
) {
    actions.forEach { action ->
        DropdownMenuItem(
            text = { Text(action.label) },
            leadingIcon = { Icon(action.icon, contentDescription = null) },
            enabled = action.enabled,
            onClick = {
                onDismiss()
                action.onClick()
            },
        )
    }
}
