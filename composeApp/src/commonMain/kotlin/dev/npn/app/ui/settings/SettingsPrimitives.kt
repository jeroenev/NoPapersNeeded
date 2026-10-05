package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HelpText

// The building blocks only settings panes use; the ones other screens share live in ui/common.

/** Vertical gap between an action button and its help copy. */
private val ActionSpacing = 8.dp

/** A button that performs a one-off action (open a sub-screen, clear a cache), plus help copy. */
@Composable
internal fun SettingsActionRow(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ActionSpacing)) {
        OutlinedButton(onClick = onClick) { Text(text = label) }
        if (help != null) HelpText(help)
    }
}
