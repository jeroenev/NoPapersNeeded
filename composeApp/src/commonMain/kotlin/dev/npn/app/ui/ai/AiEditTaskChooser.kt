package dev.npn.app.ui.ai

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.ai_edit_task_change
import no_papers_needed.composeapp.generated.resources.ai_edit_task_clean_tags
import no_papers_needed.composeapp.generated.resources.ai_edit_task_clean_tags_help
import no_papers_needed.composeapp.generated.resources.ai_edit_task_custom
import no_papers_needed.composeapp.generated.resources.ai_edit_task_custom_help
import no_papers_needed.composeapp.generated.resources.ai_edit_task_fill
import no_papers_needed.composeapp.generated.resources.ai_edit_task_fill_help
import no_papers_needed.composeapp.generated.resources.ai_edit_task_organize
import no_papers_needed.composeapp.generated.resources.ai_edit_task_organize_help
import no_papers_needed.composeapp.generated.resources.ai_edit_task_question
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/** Icon size inside a task card; large enough to read as an illustration, not a bullet. */
private val TaskIconSize = 24.dp

/**
 * The first question the page asks: which job are we doing?
 *
 * Every card stands for a combination of knobs the form below can also express by hand (see
 * [EditTask]). The cards exist because the knobs are the wrong opening move — a run is configured
 * far more often to do one of four ordinary things than to do something bespoke.
 */
@Composable
internal fun TaskChooser(
    onPick: (EditTask) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(Res.string.ai_edit_task_question), style = MaterialTheme.typography.titleMedium)
        EditTask.entries.forEach { task -> TaskCard(task, onPick = onPick) }
    }
}

/**
 * The chosen task, with the way back to the chooser. Shown in place of the cards once a task is
 * picked, so the form is never preceded by four large cards competing with it.
 */
@Composable
internal fun ChosenTask(
    task: EditTask,
    onChange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(task.icon, contentDescription = null, modifier = Modifier.size(TaskIconSize))
        Text(
            stringResource(task.label),
            Modifier.weight(1f),
            style = MaterialTheme.typography.titleMedium,
        )
        TextButton(onClick = onChange) { Text(stringResource(Res.string.ai_edit_task_change)) }
    }
}

@Composable
private fun TaskCard(
    task: EditTask,
    onPick: (EditTask) -> Unit,
) {
    OutlinedCard(
        Modifier
            .fillMaxWidth()
            // Never selected: the cards are only on screen while no task is chosen, and picking
            // one replaces them with the form. The role still makes it one of a set of choices.
            .selectable(selected = false, role = Role.RadioButton) { onPick(task) },
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                task.icon,
                contentDescription = null,
                modifier = Modifier.size(TaskIconSize),
                tint = MaterialTheme.colorScheme.primary,
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(task.label), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(task.help),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private val EditTask.label: StringResource
    get() =
        when (this) {
            EditTask.ORGANIZE_UNTAGGED -> Res.string.ai_edit_task_organize
            EditTask.FILL_FIELDS -> Res.string.ai_edit_task_fill
            EditTask.CLEAN_UP_TAGS -> Res.string.ai_edit_task_clean_tags
            EditTask.CUSTOM -> Res.string.ai_edit_task_custom
        }

private val EditTask.help: StringResource
    get() =
        when (this) {
            EditTask.ORGANIZE_UNTAGGED -> Res.string.ai_edit_task_organize_help
            EditTask.FILL_FIELDS -> Res.string.ai_edit_task_fill_help
            EditTask.CLEAN_UP_TAGS -> Res.string.ai_edit_task_clean_tags_help
            EditTask.CUSTOM -> Res.string.ai_edit_task_custom_help
        }

private val EditTask.icon: ImageVector
    get() =
        when (this) {
            EditTask.ORGANIZE_UNTAGGED -> Icons.AutoMirrored.Outlined.Label
            EditTask.FILL_FIELDS -> Icons.Outlined.EditNote
            EditTask.CLEAN_UP_TAGS -> Icons.Outlined.CleaningServices
            EditTask.CUSTOM -> Icons.Outlined.AutoFixHigh
        }
