package dev.npn.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/**
 * A labelled yes/no setting with optional [help] underneath. The whole row is the toggle, so the
 * label is a target too and a screen reader announces one switch with its name, not a bare switch
 * beside some text.
 */
@Composable
fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    help: String? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            help?.let { HelpText(it) }
        }
        // Null callback: the row is the toggle, so the switch must not be a second target.
        Switch(checked = checked, enabled = enabled, onCheckedChange = null)
    }
}

/**
 * A labelled checkbox. Like [SwitchRow], the whole row is the toggle and the box only shows the
 * state, so the label is a target too and a screen reader announces one checkbox with its name.
 */
@Composable
fun CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    CheckboxRow(checked = checked, onCheckedChange = onCheckedChange, modifier = modifier) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
    }
}

/** [CheckboxRow] with [content] richer than a label beside the box, the row still the one target. */
@Composable
fun CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Null callback: the row is the toggle, so the box must not be a second target.
        Checkbox(checked = checked, onCheckedChange = null)
        content()
    }
}

/**
 * One choice of a radio group, labelled, with optional [supporting] content under the label. Like
 * [SwitchRow], the whole row is the target and the button only shows the state, so a screen reader
 * announces one radio button with its name.
 */
@Composable
fun RadioRow(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: (@Composable ColumnScope.() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect),
        // With supporting lines under the label, the button stays level with the label.
        verticalAlignment = if (supporting == null) Alignment.CenterVertically else Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Null callback: the row is the target, so the button must not be a second one.
        RadioButton(selected = selected, onClick = null)
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            supporting?.invoke(this)
        }
    }
}
