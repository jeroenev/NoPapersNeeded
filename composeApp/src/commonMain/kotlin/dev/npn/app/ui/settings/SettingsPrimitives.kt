package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.HelpText

// The building blocks every settings pane is made of. Before these existed each pane hand-rolled
// the same `Row` + weighted label column + trailing `Switch` (five times) and the same
// `OutlinedButton` + `DropdownMenu` (four times), which is why adding a setting used to mean
// copying twenty lines. Panes now describe *what* they configure, not how a row is laid out.

/** Vertical gap between the controls inside one [SettingsGroup]. */
private val GroupSpacing = 8.dp

/**
 * One titled cluster of related controls. Groups replace the old flat divider-separated run of
 * sections: a pane holds a handful of groups, and the pane host spaces them apart.
 */
@Composable
internal fun SettingsGroup(
    title: String?,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GroupSpacing)) {
        if (title != null) SettingsSubheading(title)
        content()
    }
}

@Composable
internal fun SettingsSubheading(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary,
        // Marks each group title as a heading so screen readers can jump group to group down a long
        // pane instead of walking every control. This is the in-pane structure improvement that is
        // possible without owning the pane's scroll container (see the sticky-heading blocker note).
        modifier = Modifier.semantics { heading() },
    )
}

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
    Column(modifier, verticalArrangement = Arrangement.spacedBy(GroupSpacing)) {
        OutlinedButton(onClick = onClick) { Text(text = label) }
        if (help != null) HelpText(help)
    }
}
