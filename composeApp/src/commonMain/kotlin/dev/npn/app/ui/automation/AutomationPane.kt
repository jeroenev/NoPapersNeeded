package dev.npn.app.ui.automation

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.settings.SettingsActionRow
import dev.npn.app.ui.settings.SettingsCategoryId
import dev.npn.app.ui.settings.SettingsPaneActions
import dev.npn.app.ui.settings.SettingsPaneState
import dev.npn.core.model.ReviewMode
import dev.npn.core.rules.AutoMatchRule
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.automation_ai_help
import no_papers_needed.composeapp.generated.resources.automation_ai_open
import no_papers_needed.composeapp.generated.resources.automation_ai_state_no_ai
import no_papers_needed.composeapp.generated.resources.automation_ai_state_off
import no_papers_needed.composeapp.generated.resources.automation_ai_state_on
import no_papers_needed.composeapp.generated.resources.automation_ai_title
import no_papers_needed.composeapp.generated.resources.automation_inbox_help
import no_papers_needed.composeapp.generated.resources.automation_inbox_open
import no_papers_needed.composeapp.generated.resources.automation_inbox_state_off
import no_papers_needed.composeapp.generated.resources.automation_inbox_state_on
import no_papers_needed.composeapp.generated.resources.automation_inbox_title
import no_papers_needed.composeapp.generated.resources.automation_intro
import no_papers_needed.composeapp.generated.resources.automation_manual_help
import no_papers_needed.composeapp.generated.resources.automation_manual_title
import no_papers_needed.composeapp.generated.resources.automation_rules_help
import no_papers_needed.composeapp.generated.resources.automation_rules_none
import no_papers_needed.composeapp.generated.resources.automation_rules_state
import no_papers_needed.composeapp.generated.resources.automation_rules_title
import no_papers_needed.composeapp.generated.resources.common_settings_auto_matching_rules
import no_papers_needed.composeapp.generated.resources.common_settings_syncs_across_devices
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Automation overview: what organizes a new document, in the order it runs. Rules first (free,
 * instant, synced with the library), then the assistant's auto-organize if this device has it on,
 * then the Inbox for whatever is left. Each step says whether it is on and leads to where it is
 * changed; nothing is switched here, so each setting keeps one home and its own safeguards (the
 * inbox's migration prompt, the assistant's setup).
 */
@Composable
internal fun AutomationSettingsPane(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    SettingsSection(title = null) {
        HelpText(stringResource(Res.string.automation_intro))
    }

    SettingsSection(title = stringResource(Res.string.automation_rules_title)) {
        HelpText(stringResource(Res.string.automation_rules_help))
        state.rules?.let { StateLine(rulesState(it)) }
        HelpText(stringResource(Res.string.common_settings_syncs_across_devices))
        SettingsActionRow(
            label = stringResource(Res.string.common_settings_auto_matching_rules),
            onClick = actions::openAutoMatchRules,
        )
    }

    SettingsSection(title = stringResource(Res.string.automation_ai_title)) {
        HelpText(stringResource(Res.string.automation_ai_help))
        val snapshot = state.settings.snapshot
        StateLine(
            stringResource(
                when {
                    !snapshot.aiEnabled -> Res.string.automation_ai_state_no_ai
                    snapshot.aiAutoOnOcr -> Res.string.automation_ai_state_on
                    else -> Res.string.automation_ai_state_off
                },
            ),
        )
        SettingsActionRow(
            label = stringResource(Res.string.automation_ai_open),
            onClick = { actions.openCategory(SettingsCategoryId.AI) },
        )
    }

    SettingsSection(title = stringResource(Res.string.automation_inbox_title)) {
        HelpText(stringResource(Res.string.automation_inbox_help))
        StateLine(
            stringResource(
                if (state.reviewMode == ReviewMode.INBOX_WORKFLOW) {
                    Res.string.automation_inbox_state_on
                } else {
                    Res.string.automation_inbox_state_off
                },
            ),
        )
        HelpText(stringResource(Res.string.common_settings_syncs_across_devices))
        SettingsActionRow(
            label = stringResource(Res.string.automation_inbox_open),
            onClick = { actions.openCategory(SettingsCategoryId.DOCUMENTS) },
        )
    }

    SettingsSection(title = stringResource(Res.string.automation_manual_title)) {
        HelpText(stringResource(Res.string.automation_manual_help))
    }
}

/** How many of [rules] are on, or that there are none. */
@Composable
private fun rulesState(rules: List<AutoMatchRule>): String =
    if (rules.isEmpty()) {
        stringResource(Res.string.automation_rules_none)
    } else {
        pluralStringResource(Res.plurals.automation_rules_state, rules.size, rules.count { it.enabled }, rules.size)
    }

/** Whether a step is on, set apart from the help around it. */
@Composable
private fun StateLine(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
}
