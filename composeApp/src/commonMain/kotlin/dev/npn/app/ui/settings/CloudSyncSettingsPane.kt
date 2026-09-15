package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.common.TypedTextField
import dev.npn.app.ui.common.formatEpochDateTime
import dev.npn.app.ui.common.resolve
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncPhase
import dev.npn.core.sync.SyncProgress
import dev.npn.core.sync.SyncRunSummary
import dev.npn.core.sync.TransportAvailability
import kotlinx.coroutines.delay
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_advanced
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_auto
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_auto_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_binary
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_binary_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_config
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_config_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_configured_remotes
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_conflict_count
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_available
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_check
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_checking
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_missing
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_error_count
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_failure_unknown
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_interval
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_interval_hours
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_interval_minutes
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_last_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_last_summary
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_load_remotes
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_never
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_no_remotes
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_outcome_completed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_outcome_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_outcome_not_configured
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_outcome_running
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_outcome_unavailable
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_phase_apply_local
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_phase_indexing
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_phase_listing
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_phase_pull_metadata
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_phase_push_documents
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_phase_push_metadata
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_phase_resolve
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_problem_line
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_progress
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_reload_remotes
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_remote
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_remote_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_remotes_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_schedule
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_status
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sync_now
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Example remote shown with the remote field. */
private const val REMOTE_EXAMPLE = "gdrive:NoPapersNeeded"

/** Interval choices offered for automatic passes, in minutes. */
private val INTERVAL_OPTIONS = listOf(5, 15, 30, 60, 180, 720, 1440)

private const val MINUTES_PER_HOUR = 60

/**
 * How long a path field must be quiet before rclone is probed with it: past the pause after which
 * what is typed is saved, as the engine reads the saved paths.
 */
private const val PROBE_DEBOUNCE_MS = 1_000L

private const val MAX_LISTED_PROBLEMS = 5

/** The gap between the controls of one group, as [SettingsSection] spaces its own. */
private val ItemSpacing = 8.dp

/**
 * Cloud synchronization through rclone: whether the engine is usable on this device, which remote
 * the library mirrors to, the automatic schedule, a manual pass with its progress and outcome,
 * and the two advanced paths (executable, config file).
 */
@Composable
internal fun CloudSyncSettingsPane(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    val settings = state.settings
    val snapshot = settings.snapshot
    val cloud = state.cloud
    // The executable and config paths decide whether rclone answers; probe again when either
    // changes, once typing has paused. The effect restarting on the next keystroke absorbs a burst.
    LaunchedEffect(snapshot.rclonePath, snapshot.rcloneConfigPath) {
        if (cloud.transport != null) delay(PROBE_DEBOUNCE_MS)
        actions.probeTransport()
    }
    val available = cloud.transport is TransportAvailability.Available

    SettingsSection(title = null) {
        HelpText(stringResource(Res.string.common_settings_cloud_help))
        EngineStatus(cloud.transport, onCheck = actions::probeTransport)
    }

    SettingsSection(title = stringResource(Res.string.common_settings_cloud_remote)) {
        RemoteEditor(snapshot.cloudRemote.orEmpty(), cloud, actions, available)
    }

    SettingsSection(title = stringResource(Res.string.common_settings_cloud_schedule)) {
        SwitchRow(
            label = stringResource(Res.string.common_settings_cloud_auto),
            help = stringResource(Res.string.common_settings_cloud_auto_help),
            checked = snapshot.cloudSyncEnabled,
            // Turning it on needs a working engine and a remote; turning it off must always work,
            // or a remote cleared afterwards leaves the switch stuck on.
            enabled = snapshot.cloudSyncEnabled || (available && settings.cloudConfigured),
            onCheckedChange = { on -> actions.edit { it.copy(cloudSyncEnabled = on) } },
        )
        val interval = intervalLabel(snapshot.cloudSyncIntervalMinutes)
        Dropdown(
            selected = stringResource(Res.string.common_settings_cloud_interval, interval),
            options = INTERVAL_OPTIONS,
            optionLabel = { intervalLabel(it) },
            onSelect = { minutes -> actions.edit { it.copy(cloudSyncIntervalMinutes = minutes) } },
        )
    }

    SettingsSection(title = stringResource(Res.string.common_settings_cloud_status)) {
        SyncStatusSection(cloud, available && settings.cloudConfigured, actions::syncNow)
    }

    SettingsSection(title = stringResource(Res.string.common_settings_advanced)) {
        TypedTextField(
            value = snapshot.rclonePath.orEmpty(),
            onValueChange = { path -> actions.type(TextSetting.RCLONE_PATH, path) },
            onDone = { actions.doneTyping(TextSetting.RCLONE_PATH) },
            label = stringResource(Res.string.common_settings_cloud_binary),
            supportingText = { Text(text = stringResource(Res.string.common_settings_cloud_binary_help)) },
        )
        TypedTextField(
            value = snapshot.rcloneConfigPath.orEmpty(),
            onValueChange = { path -> actions.type(TextSetting.RCLONE_CONFIG_PATH, path) },
            onDone = { actions.doneTyping(TextSetting.RCLONE_CONFIG_PATH) },
            label = stringResource(Res.string.common_settings_cloud_config),
            supportingText = { Text(text = stringResource(Res.string.common_settings_cloud_config_help)) },
        )
    }
}

@Composable
private fun EngineStatus(
    transport: TransportAvailability?,
    onCheck: () -> Unit,
) {
    val text =
        when (transport) {
            null -> {
                stringResource(Res.string.common_settings_cloud_engine_checking)
            }

            is TransportAvailability.Available -> {
                stringResource(Res.string.common_settings_cloud_engine_available, transport.version)
            }

            is TransportAvailability.NotInstalled -> {
                stringResource(Res.string.common_settings_cloud_engine_missing, transport.lookedFor)
            }

            is TransportAvailability.Failed -> {
                stringResource(Res.string.common_settings_cloud_engine_failed, transport.message)
            }
        }
    Text(text = text, style = MaterialTheme.typography.bodyMedium)
    if (transport !is TransportAvailability.Available) {
        SettingsActionRow(label = stringResource(Res.string.common_settings_cloud_engine_check), onClick = onCheck)
    }
}

@Composable
private fun RemoteEditor(
    remote: String,
    cloud: CloudSyncState,
    actions: SettingsActions,
    available: Boolean,
) {
    TypedTextField(
        value = remote,
        onValueChange = { typed -> actions.type(TextSetting.CLOUD_REMOTE, typed) },
        onDone = { actions.doneTyping(TextSetting.CLOUD_REMOTE) },
        label = stringResource(Res.string.common_settings_cloud_remote),
        supportingText = { Text(text = stringResource(Res.string.common_settings_cloud_remote_help, REMOTE_EXAMPLE)) },
    )
    val remotes = cloud.remotes.remotes
    Row(horizontalArrangement = Arrangement.spacedBy(ItemSpacing)) {
        if (!remotes.isNullOrEmpty()) {
            // Picking a name fills in `name:`; the folder on that remote is the user's to add.
            Dropdown(
                selected = stringResource(Res.string.common_settings_cloud_configured_remotes),
                options = remotes,
                optionLabel = { it },
                onSelect = { name ->
                    actions.type(TextSetting.CLOUD_REMOTE, "$name:")
                    actions.doneTyping(TextSetting.CLOUD_REMOTE)
                },
            )
        }
        // Always offered, so a remote added with `rclone config` while this screen is open can be
        // picked up without leaving Settings.
        OutlinedButton(onClick = actions::loadRemotes, enabled = available && !cloud.status.running) {
            val label =
                if (remotes == null) {
                    Res.string.common_settings_cloud_load_remotes
                } else {
                    Res.string.common_settings_cloud_reload_remotes
                }
            Text(text = stringResource(label))
        }
    }
    val error = cloud.remotes.error
    if (error != null) {
        HelpText(stringResource(Res.string.common_settings_cloud_remotes_failed, error.resolve()))
    } else if (remotes != null && remotes.isEmpty()) {
        HelpText(stringResource(Res.string.common_settings_cloud_no_remotes))
    }
}

@Composable
private fun SyncStatusSection(
    cloud: CloudSyncState,
    canSync: Boolean,
    onSyncNow: () -> Unit,
) {
    val status = cloud.status
    Column(verticalArrangement = Arrangement.spacedBy(ItemSpacing)) {
        Button(onClick = onSyncNow, enabled = canSync && !status.running) {
            Text(text = stringResource(Res.string.common_settings_cloud_sync_now))
        }
        val progress = status.progress
        if (status.running && progress != null) {
            Text(text = progressLabel(progress), style = MaterialTheme.typography.bodyMedium)
            if (progress.total > 0) {
                LinearProgressIndicator(
                    progress = { progress.done.toFloat() / progress.total.toFloat() },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
        }
        LastRunSummary(status.lastRun)
        val outcome = cloud.outcome
        if (outcome != null && !status.running) {
            HelpText(outcomeLabel(outcome))
        }
    }
}

@Composable
private fun LastRunSummary(lastRun: SyncRunSummary?) {
    // A failed pass has no report worth listing problems from, only its reason.
    val report = lastRun?.report?.takeIf { lastRun.failure == null }
    Column(verticalArrangement = Arrangement.spacedBy(ItemSpacing)) {
        HelpText(lastRunText(lastRun))
        if (report != null) {
            val conflicts = report.conflicts.size
            ProblemList(
                pluralStringResource(Res.plurals.common_settings_cloud_conflict_count, conflicts, conflicts),
                report.conflicts.map { it.remotePath to it.message },
            )
            val errors = report.errors.size
            ProblemList(
                pluralStringResource(Res.plurals.common_settings_cloud_error_count, errors, errors),
                report.errors.map { it.path to it.message },
            )
        }
    }
}

/** When the last pass ended and what it did, or why it failed; that there was none yet. */
@Composable
private fun lastRunText(lastRun: SyncRunSummary?): String {
    if (lastRun == null) return stringResource(Res.string.common_settings_cloud_never)
    val finishedAt = formatEpochDateTime(lastRun.finishedAt)
    val report = lastRun.report
    val failure = lastRun.failure
    if (failure != null || report == null) {
        val reason = failure ?: stringResource(Res.string.common_settings_cloud_failure_unknown)
        return stringResource(Res.string.common_settings_cloud_last_failed, finishedAt, reason)
    }
    return stringResource(
        Res.string.common_settings_cloud_last_summary,
        finishedAt,
        report.downloadedDocuments + report.downloadedObjects,
        report.uploadedDocuments + report.uploadedObjects,
        report.movedRemote,
        report.deletedRemote,
    )
}

/** A count of [problems] (path, message), and the first few of them; nothing when there are none. */
@Composable
private fun ProblemList(
    heading: String,
    problems: List<Pair<String, String>>,
) {
    if (problems.isEmpty()) return
    Column {
        HelpText(heading)
        for ((path, message) in problems.take(MAX_LISTED_PROBLEMS)) {
            Text(
                text = stringResource(Res.string.common_settings_cloud_problem_line, path, message),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun intervalLabel(minutes: Int): String =
    if (minutes >= MINUTES_PER_HOUR && minutes % MINUTES_PER_HOUR == 0) {
        val hours = minutes / MINUTES_PER_HOUR
        pluralStringResource(Res.plurals.common_settings_cloud_interval_hours, hours, hours)
    } else {
        pluralStringResource(Res.plurals.common_settings_cloud_interval_minutes, minutes, minutes)
    }

@Composable
private fun progressLabel(progress: SyncProgress): String {
    val phase =
        when (progress.phase) {
            SyncPhase.LISTING -> stringResource(Res.string.common_settings_cloud_phase_listing)
            SyncPhase.PULL_METADATA -> stringResource(Res.string.common_settings_cloud_phase_pull_metadata)
            SyncPhase.RESOLVE_DOCUMENTS -> stringResource(Res.string.common_settings_cloud_phase_resolve)
            SyncPhase.APPLY_LOCAL -> stringResource(Res.string.common_settings_cloud_phase_apply_local)
            SyncPhase.PUSH_DOCUMENTS -> stringResource(Res.string.common_settings_cloud_phase_push_documents)
            SyncPhase.PUSH_METADATA -> stringResource(Res.string.common_settings_cloud_phase_push_metadata)
            SyncPhase.INDEXING -> stringResource(Res.string.common_settings_cloud_phase_indexing)
        }
    return if (progress.total > 0) {
        stringResource(Res.string.common_settings_cloud_progress, phase, progress.done, progress.total)
    } else {
        phase
    }
}

@Composable
private fun outcomeLabel(outcome: SyncOutcome): String =
    when (outcome) {
        is SyncOutcome.Completed -> stringResource(Res.string.common_settings_cloud_outcome_completed)
        is SyncOutcome.Failed -> stringResource(Res.string.common_settings_cloud_outcome_failed, outcome.message)
        SyncOutcome.NotConfigured -> stringResource(Res.string.common_settings_cloud_outcome_not_configured)
        is SyncOutcome.Unavailable -> stringResource(Res.string.common_settings_cloud_outcome_unavailable)
        SyncOutcome.AlreadyRunning -> stringResource(Res.string.common_settings_cloud_outcome_running)
    }
