package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.SettingsSection
import dev.npn.core.ai.AiFieldMode
import dev.npn.core.app.EditScope
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_field_mode_all
import no_papers_needed.composeapp.generated.resources.ai_edit_field_mode_unfilled
import no_papers_needed.composeapp.generated.resources.ai_edit_scope_count_batch
import no_papers_needed.composeapp.generated.resources.common_document_count
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

// The building blocks the AI-edit setup form is assembled from. They live apart from the form
// itself so the page reads as "which sections exist" rather than "how a row is laid out".

/** The two field modes in the order a picker should offer them: the safe one first. */
private val FieldModes = listOf(AiFieldMode.UNFILLED, AiFieldMode.ALL)

/** One group of the setup form: a [SettingsSection] with the denser form's smaller heading. */
@Composable
internal fun SetupSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) = SettingsSection(
    title = title,
    modifier = Modifier.fillMaxWidth(),
    headingStyle = MaterialTheme.typography.titleSmall,
    content = content,
)

@Composable
internal fun SetupColumn(
    modifier: Modifier,
    content: @Composable ColumnScope.() -> Unit,
) = Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp), content = content)

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
    Dropdown(
        selected = fieldModeLabel(mode),
        options = FieldModes,
        optionLabel = { fieldModeLabel(it) },
        onSelect = onModeChange,
        modifier = Modifier.padding(start = 40.dp),
        outlined = false,
    )
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
