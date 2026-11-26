package dev.npn.app.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.npn.app.platform.shareDiagnosticsBundle
import dev.npn.app.platform.supportsSharing
import dev.npn.app.ui.common.HelpText
import dev.npn.core.util.runCatchingCancellable
import io.github.vinceglb.filekit.dialogs.FileKitDialogSettings
import io.github.vinceglb.filekit.dialogs.compose.rememberFileSaverLauncher
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_cancel
import no_papers_needed.composeapp.generated.resources.common_save
import no_papers_needed.composeapp.generated.resources.common_settings_copy_path
import no_papers_needed.composeapp.generated.resources.common_settings_diagnostics_preview_help
import no_papers_needed.composeapp.generated.resources.common_settings_diagnostics_preview_title
import no_papers_needed.composeapp.generated.resources.common_settings_diagnostics_saved
import no_papers_needed.composeapp.generated.resources.common_settings_diagnostics_saved_help
import no_papers_needed.composeapp.generated.resources.common_settings_diagnostics_share
import no_papers_needed.composeapp.generated.resources.common_settings_diagnostics_shared_help
import no_papers_needed.composeapp.generated.resources.common_settings_open_issue
import no_papers_needed.composeapp.generated.resources.common_settings_path_copied
import org.jetbrains.compose.resources.stringResource

/**
 * Shows the bundle built for a report, then writes it and opens the prefilled issue: a save dialog
 * on desktop, the cache plus share sheet on Android. The user reads the whole text first and can
 * still let it go; nothing leaves the device until they save or share. A failed write goes nowhere
 * near a crash; the next tap builds a fresh bundle.
 */
@Composable
internal fun ProblemReportHandler(
    report: ProblemReportState,
    actions: ProblemReportActions,
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
    // The bundle the user agreed to share after reading it, from then until the report is idle.
    var handing by remember(report is ProblemReportState.Ready) { mutableStateOf<ProblemReportState.Ready?>(null) }

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

    val ready = report as? ProblemReportState.Ready
    if (ready != null && handing != ready) {
        ProblemReportPreview(
            text = ready.text,
            onShare = { handing = ready },
            onCancel = actions::problemReportHandled,
        )
    }

    LaunchedEffect(handing) {
        val shared = handing ?: return@LaunchedEffect
        if (supportsSharing()) {
            runCatchingCancellable { shareDiagnosticsBundle(shared.fileName, shared.text) }
                .onSuccess { path ->
                    savedPath = path
                    issueUrl = shared.issueUrl
                    copied = false
                    // The chooser is an activity of its own; opening the issue now would cover it.
                    actions.problemReportHandled()
                }.onFailure { actions.problemReportFailed(it) }
        } else {
            pending = shared
            saver.launch(suggestedName = shared.fileName.substringBeforeLast('.'), defaultExtension = "txt")
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

/** The whole bundle, as it will be written, before the share sheet or save dialog. */
@Composable
private fun ProblemReportPreview(
    text: String,
    onShare: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(Res.string.common_settings_diagnostics_preview_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                HelpText(stringResource(Res.string.common_settings_diagnostics_preview_help))
                SelectionContainer(
                    Modifier.fillMaxWidth().heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                ) {
                    Text(text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            val label =
                if (supportsSharing()) Res.string.common_settings_diagnostics_share else Res.string.common_save
            TextButton(onClick = onShare) { Text(stringResource(label)) }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text(stringResource(Res.string.common_cancel)) }
        },
    )
}
