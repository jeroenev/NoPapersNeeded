package dev.npn.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import dev.npn.app.BuildInfo
import dev.npn.app.platform.checksForUpdates
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.diagnostics.ProblemReportHandler
import dev.npn.app.ui.diagnostics.ProblemReportState
import dev.npn.app.ui.whatsnew.WhatsNewDialog
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_settings_about_help
import no_papers_needed.composeapp.generated.resources.common_settings_product_version
import no_papers_needed.composeapp.generated.resources.common_settings_report_problem
import no_papers_needed.composeapp.generated.resources.common_settings_report_problem_building
import no_papers_needed.composeapp.generated.resources.common_settings_report_problem_help
import no_papers_needed.composeapp.generated.resources.common_settings_support_help
import no_papers_needed.composeapp.generated.resources.common_settings_support_sponsor
import no_papers_needed.composeapp.generated.resources.common_settings_support_title
import no_papers_needed.composeapp.generated.resources.common_settings_update_check
import no_papers_needed.composeapp.generated.resources.common_settings_update_check_help
import no_papers_needed.composeapp.generated.resources.common_settings_whats_new
import org.jetbrains.compose.resources.stringResource

/** Where the sponsor button in About goes: the maintainer's GitHub Sponsors page. */
private const val SPONSORS_URL = "https://github.com/sponsors/jeroenev"

@Composable
internal fun AboutSettingsPane(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    val uriHandler = LocalUriHandler.current
    var whatsNew by rememberSaveable { mutableStateOf(false) }
    SettingsSection(title = null) {
        Text(
            text =
                stringResource(
                    Res.string.common_settings_product_version,
                    stringResource(Res.string.common_app_name),
                    BuildInfo.VERSION,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
        HelpText(stringResource(Res.string.common_settings_about_help))
        SettingsActionRow(
            label = stringResource(Res.string.common_settings_whats_new),
            onClick = { whatsNew = true },
        )
        // Inside Flatpak the package manager updates the app, so there is nothing to switch.
        if (checksForUpdates()) {
            SwitchRow(
                label = stringResource(Res.string.common_settings_update_check),
                help = stringResource(Res.string.common_settings_update_check_help),
                checked = state.settings.snapshot.updateCheckEnabled,
                onCheckedChange = { on -> actions.edit { it.copy(updateCheckEnabled = on) } },
            )
        }
    }
    SettingsSection(title = stringResource(Res.string.common_settings_support_title)) {
        HelpText(stringResource(Res.string.common_settings_support_help))
        SettingsActionRow(
            label = stringResource(Res.string.common_settings_support_sponsor),
            onClick = { runCatching { uriHandler.openUri(SPONSORS_URL) } },
        )
    }
    SettingsSection(title = null) {
        if (state.problemReport !is ProblemReportState.Idle) {
            HelpText(stringResource(Res.string.common_settings_report_problem_building))
        } else {
            SettingsActionRow(
                label = stringResource(Res.string.common_settings_report_problem),
                onClick = actions::reportProblem,
                help = stringResource(Res.string.common_settings_report_problem_help),
            )
        }
    }

    if (whatsNew) {
        WhatsNewDialog(
            onDismiss = { whatsNew = false },
        )
    }

    ProblemReportHandler(state.problemReport, actions)
}
