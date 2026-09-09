@file:OptIn(ExperimentalMaterial3Api::class)

package dev.npn.app.ui.common

import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Icon button whose label is exposed as a hover/long-press tooltip (and as the content
 * description for accessibility). Used for compact top-bar actions.
 */
@Composable
fun TooltipIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    badge: (@Composable () -> Unit)? = null,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        IconButton(onClick = onClick, enabled = enabled) {
            if (badge == null) {
                Icon(icon, contentDescription = label)
            } else {
                BadgedBox(badge = { badge() }) { Icon(icon, contentDescription = label) }
            }
        }
    }
}
