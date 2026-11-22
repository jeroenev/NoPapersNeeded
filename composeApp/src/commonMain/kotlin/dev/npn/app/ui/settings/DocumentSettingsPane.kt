package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import dev.npn.app.platform.rememberReminderPermissionRequest
import dev.npn.app.ui.common.CheckboxRow
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.NumericField
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.common.TypedTextField
import dev.npn.core.app.ReminderSettings
import dev.npn.core.app.TrashSettings
import dev.npn.core.app.parseReminderTime
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
import no_papers_needed.composeapp.generated.resources.common_settings_reminders
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_enabled
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_enabled_help
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_fields_help
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_lead_days
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_lead_days_help
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_no_date_fields
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_time
import no_papers_needed.composeapp.generated.resources.common_settings_reminders_time_help
import no_papers_needed.composeapp.generated.resources.common_settings_review_workflow
import no_papers_needed.composeapp.generated.resources.common_settings_syncs_across_devices
import no_papers_needed.composeapp.generated.resources.common_settings_trash
import no_papers_needed.composeapp.generated.resources.common_settings_trash_retention_days
import no_papers_needed.composeapp.generated.resources.library_trash_retention_zero_help
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
            onUntaggedOnly = actions::setUntaggedOnlyMode,
            onInboxWorkflow = actions::setInboxWorkflowMode,
        )
    }

    RemindersSection(state, actions)

    SettingsSection(title = stringResource(Res.string.common_settings_trash)) {
        NumericField(
            value = state.settings.trashRetentionDays,
            onValueChange = actions::setTrashRetentionDays,
            range = TrashSettings.RETENTION_RANGE,
            label = stringResource(Res.string.common_settings_trash_retention_days),
            onInvalid = { actions.setTrashRetentionDays(null) },
            onDone = actions::doneTypingTrashRetention,
        )
        HelpText(stringResource(Res.string.library_trash_retention_zero_help))
        HelpText(stringResource(Res.string.common_settings_syncs_across_devices))
    }
}

/**
 * Reminders watch DATE fields in the library and notify on a daily schedule. The setting is library
 * data (synced through the journal), so the fields and their times are the same on every device;
 * what this device already notified lives in `device.db`.
 */
@Composable
private fun RemindersSection(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    val requestPermission = rememberReminderPermissionRequest()
    val dateFields = state.fields.filter { it.type.isDate }
    SettingsSection(title = stringResource(Res.string.common_settings_reminders)) {
        SwitchRow(
            label = stringResource(Res.string.common_settings_reminders_enabled),
            help = stringResource(Res.string.common_settings_reminders_enabled_help),
            checked = state.settings.reminderEnabled,
            onCheckedChange = { enabled ->
                actions.setRemindersEnabled(enabled)
                if (enabled) requestPermission?.invoke()
            },
        )
        if (state.settings.reminderEnabled) {
            if (dateFields.isEmpty()) {
                HelpText(stringResource(Res.string.common_settings_reminders_no_date_fields))
            } else {
                HelpText(stringResource(Res.string.common_settings_reminders_fields_help))
                dateFields.forEach { field ->
                    CheckboxRow(
                        label = field.label,
                        checked = field.key in state.settings.reminderFieldKeys,
                        onCheckedChange = { selected -> actions.setReminderField(field.key, selected) },
                    )
                }
            }
            NumericField(
                value = state.settings.reminderLeadDays,
                onValueChange = { actions.setReminderLeadDays(it) },
                range = ReminderSettings.LEAD_DAY_RANGE,
                label = stringResource(Res.string.common_settings_reminders_lead_days),
                supportingText = stringResource(Res.string.common_settings_reminders_lead_days_help),
                onInvalid = { actions.setReminderLeadDays(null) },
                onDone = actions::doneTypingReminderLead,
            )
            TypedTextField(
                value = state.settings.reminderTime,
                onValueChange = actions::setReminderTime,
                onDone = actions::doneTypingReminderTime,
                label = stringResource(Res.string.common_settings_reminders_time),
                supportingText = { Text(stringResource(Res.string.common_settings_reminders_time_help)) },
                isError = parseReminderTime(state.settings.reminderTime) == null,
            )
        }
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
    onUntaggedOnly: () -> Unit,
    onInboxWorkflow: (ReviewMigration) -> Unit,
) {
    var showMigration by rememberSaveable { mutableStateOf(false) }

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
