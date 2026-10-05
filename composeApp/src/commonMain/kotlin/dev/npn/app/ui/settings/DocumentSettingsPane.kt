package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.NumericField
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.core.model.ReviewMode
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_settings_document_fields
import no_papers_needed.composeapp.generated.resources.common_settings_enable_inbox
import no_papers_needed.composeapp.generated.resources.common_settings_inbox_help
import no_papers_needed.composeapp.generated.resources.common_settings_inbox_migration_question
import no_papers_needed.composeapp.generated.resources.common_settings_mark_all_reviewed
import no_papers_needed.composeapp.generated.resources.common_settings_mark_none_reviewed
import no_papers_needed.composeapp.generated.resources.common_settings_mark_tagged_reviewed
import no_papers_needed.composeapp.generated.resources.common_settings_review_workflow
import no_papers_needed.composeapp.generated.resources.common_settings_syncs_across_devices
import no_papers_needed.composeapp.generated.resources.common_settings_trash
import no_papers_needed.composeapp.generated.resources.common_settings_trash_retention_days
import org.jetbrains.compose.resources.stringResource

/** Accepted range for the trash-retention field (0 = purge immediately; capped at four digits). */
private val TRASH_RETENTION_RANGE = 0..9999
private const val TRASH_RETENTION_MAX_DIGITS = 4

/**
 * What a document looks like and how long it sticks around: which fields exist, whether new
 * documents need an explicit review pass, and how long the trash holds on to deletions.
 */
@Composable
internal fun DocumentSettingsPane(context: SettingsPaneContext) {
    SettingsSection(title = stringResource(Res.string.common_settings_document_fields)) {
        FieldsSettingsPane(
            fields = context.fields,
            usage = context.fieldUsage,
            onSave = context.vm::saveField,
            onRemove = context.vm::removeField,
            onMove = context.vm::moveField,
        )
    }

    SettingsSection(title = stringResource(Res.string.common_settings_review_workflow)) {
        InboxWorkflowToggle(
            current = context.reviewMode,
            onUntaggedOnly = context.vm::setUntaggedOnlyMode,
            onInboxWorkflow = context.vm::setInboxWorkflowMode,
        )
    }

    SettingsSection(title = stringResource(Res.string.common_settings_trash)) {
        NumericField(
            value = context.state.snapshot.trashRetentionDays,
            onValueChange = { days -> context.vm.edit { it.copy(trashRetentionDays = days) } },
            range = TRASH_RETENTION_RANGE,
            label = stringResource(Res.string.common_settings_trash_retention_days),
            maxDigits = TRASH_RETENTION_MAX_DIGITS,
        )
    }
}

/**
 * Turning the inbox on is a one-way door for existing documents: they all need a reviewed marker
 * from somewhere, so enabling prompts for which migration to run. Turning it off needs no prompt:
 * the stored flag is simply ignored in the other mode.
 */
@Composable
private fun InboxWorkflowToggle(
    current: ReviewMode,
    onUntaggedOnly: () -> Unit,
    onInboxWorkflow: (ReviewMigration) -> Unit,
) {
    var showMigration by remember { mutableStateOf(false) }

    SwitchRow(
        label = stringResource(Res.string.common_settings_enable_inbox),
        help = stringResource(Res.string.common_settings_inbox_help),
        checked = current == ReviewMode.INBOX_WORKFLOW,
        onCheckedChange = { checked -> if (checked) showMigration = true else onUntaggedOnly() },
    )
    HelpText(stringResource(Res.string.common_settings_syncs_across_devices))

    if (showMigration) {
        ReviewMigrationDialog(
            onDismiss = { showMigration = false },
            onChoose = { migration ->
                onInboxWorkflow(migration)
                showMigration = false
            },
        )
    }
}

@Composable
private fun ReviewMigrationDialog(
    onDismiss: () -> Unit,
    onChoose: (ReviewMigration) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(Res.string.common_settings_enable_inbox)) },
        text = { Text(text = stringResource(Res.string.common_settings_inbox_migration_question)) },
        confirmButton = {
            Column {
                TextButton(onClick = { onChoose(ReviewMigration.MARK_ALL) }) {
                    Text(text = stringResource(Res.string.common_settings_mark_all_reviewed))
                }
                TextButton(onClick = { onChoose(ReviewMigration.MARK_TAGGED) }) {
                    Text(text = stringResource(Res.string.common_settings_mark_tagged_reviewed))
                }
                TextButton(onClick = { onChoose(ReviewMigration.MARK_NONE) }) {
                    Text(text = stringResource(Res.string.common_settings_mark_none_reviewed))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(Res.string.common_cancel)) }
        },
    )
}
