package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HelpText

// The building blocks only settings panes use; the ones other screens share live in ui/common.

/** Vertical gap between an action button and its help copy. */
private val ActionSpacing = 8.dp

/**
 * A single-choice picker. [buttonLabel] is the already-formatted button text (several settings
 * fold the current value into a sentence such as "Provider: OpenAI"), so this stays agnostic
 * about how the selection is phrased.
 */
@Composable
internal fun <T> SettingsDropdown(
    buttonLabel: String,
    options: List<T>,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier) {
        OutlinedButton(onClick = { expanded = true }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = buttonLabel)
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(text = optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

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
