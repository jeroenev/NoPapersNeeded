package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.npn.app.BuildInfo
import dev.npn.app.platform.checksForUpdates
import dev.npn.app.platform.shareDiagnosticsBundle
import dev.npn.app.platform.supportsSharing
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.whatsnew.WhatsNewDialog
import dev.npn.core.util.runCatchingCancellable
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_app_name
import no_papers_needed.composeapp.generated.resources.common_settings_about_help
import no_papers_needed.composeapp.generated.resources.common_settings_copy_path
import no_papers_needed.composeapp.generated.resources.common_settings_diagnostics_saved
import no_papers_needed.composeapp.generated.resources.common_settings_diagnostics_saved_help
import no_papers_needed.composeapp.generated.resources.common_settings_diagnostics_shared_help
import no_papers_needed.composeapp.generated.resources.common_settings_open_issue
import no_papers_needed.composeapp.generated.resources.common_settings_path_copied
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
            onDismiss = {
                whatsNew = false
                actions.markWhatsNewSeen()
            },
        )
    }

    ProblemReportHandler(state.problemReport, actions)
}

/**
 * Writes the bundle built for a report and opens the prefilled issue: a save dialog on desktop,
 * the cache plus share sheet on Android. A failed write goes nowhere near a crash; the next tap
 * builds a fresh bundle.
 */
@Composable
private fun ProblemReportHandler(
    report: ProblemReportState,
    actions: SettingsPaneActions,
) {
    val uriHandler = LocalUriHandler.current

    // The suspend LocalClipboard replacement needs a scope to copy one short path; this synchronous
    // one is still the simplest correct call and deprecation-only.
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<ProblemReportState.Ready?>(null) }
    var savedPath by rememberSaveable { mutableStateOf<String?>(null) }
    var issueUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var copied by rememberSaveable { mutableStateOf(false) }

    val saver =
        rememberFileSaverLauncher(dialogSettings = FileKitDialogSettings.createDefault()) { file ->
            val ready = pending
            pending = null
            actions.problemReportHandled()
            if (file == null || ready == null) return@rememberFileSaverLauncher
            scope.launch {
                runCatchingCancellable { file.write(ready.text.encodeToByteArray()) }
                    .onSuccess {
                        savedPath = file.toString()
                        issueUrl = ready.issueUrl
                        copied = false
                        runCatching { uriHandler.openUri(ready.issueUrl) }
                    }.onFailure { actions.problemReportFailed(it) }
            }
        }

    LaunchedEffect(report) {
        val ready = report as? ProblemReportState.Ready ?: return@LaunchedEffect
        if (supportsSharing()) {
            runCatchingCancellable { shareDiagnosticsBundle(ready.fileName, ready.text) }
                .onSuccess { path ->
                    savedPath = path
                    issueUrl = ready.issueUrl
                    copied = false
                    // The chooser is an activity of its own; opening the issue now would cover it.
                    actions.problemReportHandled()
                }.onFailure { actions.problemReportFailed(it) }
        } else {
            pending = ready
            saver.launch(suggestedName = ready.fileName.substringBeforeLast('.'), defaultExtension = "txt")
        }
    }

    val path = savedPath
    if (path != null) {
        AlertDialog(
            onDismissRequest = { savedPath = null },
            title = { Text(stringResource(Res.string.common_settings_diagnostics_saved)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (supportsSharing()) {
                        Text(stringResource(Res.string.common_settings_diagnostics_shared_help))
                    } else {
                        Text(stringResource(Res.string.common_settings_diagnostics_saved_help))
                        SelectionContainer { Text(path, Modifier.fillMaxWidth()) }
                    }
                }
            },
            confirmButton = {
                if (supportsSharing()) {
                    TextButton(
                        onClick = {
                            issueUrl?.let { url -> runCatching { uriHandler.openUri(url) } }
                        },
                    ) { Text(stringResource(Res.string.common_settings_open_issue)) }
                } else {
                    TextButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(path))
                            copied = true
                        },
                    ) {
                        val label =
                            if (copied) Res.string.common_settings_path_copied else Res.string.common_settings_copy_path
                        Text(stringResource(label))
                    }
                }
            },
            dismissButton = {
                if (!supportsSharing()) {
                    TextButton(
                        onClick = {
                            issueUrl?.let { url -> runCatching { uriHandler.openUri(url) } }
                        },
                    ) { Text(stringResource(Res.string.common_settings_open_issue)) }
                }
            },
        )
    }
}
