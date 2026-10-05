package dev.npn.app.ui.ai

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.app.EditScope
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_collapse
import no_papers_needed.composeapp.generated.resources.ai_edit_field_mode_all
import no_papers_needed.composeapp.generated.resources.ai_edit_field_mode_unfilled
import no_papers_needed.composeapp.generated.resources.ai_edit_scope_count_batch
import no_papers_needed.composeapp.generated.resources.ai_expand
import no_papers_needed.composeapp.generated.resources.common_document_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// The building blocks the AI-edit setup form is assembled from. They live apart from the form
// itself so the page reads as "which sections exist" rather than "how a row is laid out", and so
// the two review screens can reuse the expander without importing the whole setup.

/** The two field modes in the order a picker should offer them: the safe one first. */
private val FieldModes = listOf(AiFieldMode.UNFILLED, AiFieldMode.ALL)

/** Header + stacked full-width controls; the shared shape of every setup group. */
@Composable
internal fun SetupSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            // Marks each group title as a heading so a screen reader can jump section to section
            // down a long form instead of walking every control.
            modifier = Modifier.semantics { heading() },
        )
        content()
    }
}

@Composable
internal fun SetupColumn(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp), content = content)

/**
 * A row that expands or collapses what follows it.
 *
 * Deliberately a click, not a selection: an expander was previously built on `selectable`, which
 * announces the row as "selected" to a screen reader — a state this row does not have. The icon
 * carries the action's name, and the row itself is a button.
 */
@Composable
internal fun ExpanderRow(
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showIcon: Boolean = true,
    label: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, role = Role.Button, onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label()
        if (showIcon) {
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription =
                    stringResource(if (expanded) Res.string.ai_collapse else Res.string.ai_expand),
            )
        }
    }
}

/** One radio choice whose whole row is the target; the button itself is decoration. */
@Composable
internal fun ScopeOption(
    scope: EditScope,
    label: String,
    selected: EditScope,
    onSelect: (EditScope) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = scope == selected, role = Role.RadioButton) { onSelect(scope) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = scope == selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * One autofill field: an enable checkbox and, once enabled, what it may do to a value that is
 * already there.
 *
 * The mode used to be a switch labelled "Overwrite", which asked the user to infer what "off"
 * meant. Naming both outcomes ("Fill empty values only" / "Replace existing values too") is the
 * whole point, and it is also the one control on this page that can silently replace data.
 */
@Composable
internal fun FieldOption(
    label: String,
    option: AiFieldOption,
    onEnabledChange: (Boolean) -> Unit,
    onModeChange: (AiFieldMode) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .toggleable(
                    value = option.enabled,
                    role = Role.Checkbox,
                    onValueChange = onEnabledChange,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Checkbox(checked = option.enabled, onCheckedChange = null)
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        }
        if (option.enabled) FieldModePicker(option.mode, onModeChange)
    }
}

@Composable
private fun FieldModePicker(
    mode: AiFieldMode,
    onModeChange: (AiFieldMode) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 40.dp)) {
        TextButton(onClick = { expanded = true }) {
            Text(fieldModeLabel(mode), style = MaterialTheme.typography.labelLarge)
            Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            FieldModes.forEach { candidate ->
                DropdownMenuItem(
                    text = { Text(fieldModeLabel(candidate)) },
                    onClick = {
                        expanded = false
                        onModeChange(candidate)
                    },
                )
            }
        }
    }
}

@Composable
private fun fieldModeLabel(mode: AiFieldMode): String =
    stringResource(
        when (mode) {
            AiFieldMode.UNFILLED -> Res.string.ai_edit_field_mode_unfilled
            AiFieldMode.ALL -> Res.string.ai_edit_field_mode_all
        },
    )

/** "N documents" or, when the scope outgrows one run, "N documents, first M this batch". */
@Composable
internal fun scopeCount(
    count: Int,
    batchSize: Int,
): String =
    if (count > batchSize) {
        pluralStringResource(Res.plurals.ai_edit_scope_count_batch, count, count, batchSize)
    } else {
        pluralStringResource(Res.plurals.common_document_count, count, count)
    }
