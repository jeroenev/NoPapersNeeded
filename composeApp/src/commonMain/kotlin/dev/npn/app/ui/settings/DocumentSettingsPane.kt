package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.NumericField
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.core.app.TrashSettings
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

/**
 * What a document looks like and how long it sticks around: which fields exist, whether new
 * documents need an explicit review pass, and how long the trash holds on to deletions.
 */
@Composable
internal fun DocumentSettingsPane(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    SettingsSection(title = stringResource(Res.string.common_settings_document_fields)) {
        FieldsSettingsPane(
            fields = state.fields,
            usage = state.fieldUsage,
            dialog = state.fieldDialog,
            onDialog = actions::openFieldDialog,
            onSave = actions::saveField,
            onRemove = actions::removeField,
            onMove = actions::moveField,
        )
    }

    SettingsSection(title = stringResource(Res.string.common_settings_review_workflow)) {
        InboxWorkflowToggle(
            current = state.reviewMode,
            showMigration = state.confirmingInboxMigration,
            onShowMigration = actions::confirmInboxMigration,
            onUntaggedOnly = actions::setUntaggedOnlyMode,
            onInboxWorkflow = actions::setInboxWorkflowMode,
        )
    }

    SettingsSection(title = stringResource(Res.string.common_settings_trash)) {
        NumericField(
            value = state.settings.trashRetentionDays,
            onValueChange = actions::setTrashRetentionDays,
            range = TrashSettings.RETENTION_RANGE,
            label = stringResource(Res.string.common_settings_trash_retention_days),
        )
        HelpText(stringResource(Res.string.common_settings_syncs_across_devices))
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
    /** Whether it is asking which migration to run; the settings viewmodel keeps it. */
    showMigration: Boolean,
    onShowMigration: (Boolean) -> Unit,
    onUntaggedOnly: () -> Unit,
    onInboxWorkflow: (ReviewMigration) -> Unit,
) {
    SwitchRow(
        label = stringResource(Res.string.common_settings_enable_inbox),
        help = stringResource(Res.string.common_settings_inbox_help),
        checked = current == ReviewMode.INBOX_WORKFLOW,
        onCheckedChange = { checked -> if (checked) onShowMigration(true) else onUntaggedOnly() },
    )
    HelpText(stringResource(Res.string.common_settings_syncs_across_devices))

    if (showMigration) {
        ReviewMigrationDialog(
            onDismiss = { onShowMigration(false) },
            onChoose = { migration ->
                onInboxWorkflow(migration)
                onShowMigration(false)
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
