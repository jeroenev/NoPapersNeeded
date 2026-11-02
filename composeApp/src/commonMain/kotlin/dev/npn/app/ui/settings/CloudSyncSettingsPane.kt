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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.npn.app.ui.common.ConfirmDialog
import dev.npn.app.ui.common.Dropdown
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.SwitchRow
import dev.npn.app.ui.common.formatEpochDateTime
import dev.npn.app.ui.onboarding.AddDeviceGuideDialog
import dev.npn.core.sync.ConflictKind
import dev.npn.core.sync.EngineProblem
import dev.npn.core.sync.SyncConflict
import dev.npn.core.sync.SyncFailure
import dev.npn.core.sync.SyncOutcome
import dev.npn.core.sync.SyncPhase
import dev.npn.core.sync.SyncProgress
import dev.npn.core.sync.SyncRunSummary
import dev.npn.core.sync.TransportAvailability
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_auto
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_auto_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_change_folder
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_conflict_count
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_conflict_installed_as_copy
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_conflict_kept_on_device
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_conflict_local_changed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_conflict_remote_changed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_conflict_target_occupied
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_conflict_unidentified
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_conflict_uploaded_as_copy
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_disconnect
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_disconnect_message
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_disconnect_title
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_available
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_check
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_closed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_exited
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_missing
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_none
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_not_ready
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_start_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_engine_unreachable
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_error_count
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_failure_full
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_failure_journal_blocked
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_failure_not_library
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_failure_other_library
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_failure_sign_in
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_failure_unknown
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_failure_unreachable
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_help
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_interval
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_interval_hours
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_interval_minutes
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_last_failed
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_last_summary
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_never
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_outcome_completed
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
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_remote
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_schedule
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_title
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_setup_unavailable
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_status
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sync_now
import no_papers_needed.composeapp.generated.resources.onboarding_add_device
import no_papers_needed.composeapp.generated.resources.onboarding_add_device_help
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Interval choices offered for automatic passes, in minutes. */
private val INTERVAL_OPTIONS = listOf(5, 15, 30, 60, 180, 720, 1440)

private const val MINUTES_PER_HOUR = 60

private const val MAX_LISTED_PROBLEMS = 5

/** The gap between the controls of one group, as [SettingsSection] spaces its own. */
private val ItemSpacing = 8.dp

/**
 * Cloud synchronization through the bundled rclone, for this library on this device. With no remote
 * configured the pane is the in-app setup wizard (provider, sign-in, folder); once one is chosen it
 * shows the status, schedule and manual pass, plus the two ways to change that choice (a different
 * folder, or disconnect).
 */
@Composable
internal fun CloudSyncSettingsPane(
    state: SettingsPaneState,
    actions: SettingsPaneActions,
) {
    val cloud = state.cloud
    val config = cloud.config
    // Ask whether the engine is usable when the pane opens.
    DisposableEffect(Unit) {
        actions.probeTransport()
        onDispose {}
    }
    val configured = config.configured
    // With no remote, entering the pane (or disconnecting) opens the wizard on the provider list.
    // A wizard already open (the user left mid-sign-in and came back) is resumed, not restarted, and
    // cancelling leaves it idle until the user asks again, so this must not key on the wizard state.
    DisposableEffect(configured, cloud.setupAvailable) {
        if (!configured && cloud.setupAvailable && cloud.setup == null) actions.cloudSetup.start()
        onDispose {}
    }
    val available = cloud.transport is TransportAvailability.Available
    // Changing the folder, or disconnecting, must not race a pass that is reading the remote.
    val editable = !cloud.status.running

    SettingsSection(title = null) {
        HelpText(stringResource(Res.string.common_settings_cloud_help))
        EngineStatus(cloud.transport, onCheck = actions::probeTransport)
        AddDeviceAction(state)
    }

    // The wizard also takes over when it is open for an already-configured remote (the "change
    // folder" flow), not only while none is set.
    if (cloud.setup != null || !configured) {
        SettingsSection(title = stringResource(Res.string.common_settings_cloud_setup_title)) {
            if (cloud.setupAvailable) {
                CloudSetupWizard(cloud.setup, actions.cloudSetup)
            } else {
                HelpText(stringResource(Res.string.common_settings_cloud_setup_unavailable))
            }
        }
    } else {
        SettingsSection(title = stringResource(Res.string.common_settings_cloud_remote)) {
            ConnectedRemote(config.remote.orEmpty(), actions.cloudSetup, available, editable)
        }
        SettingsSection(title = stringResource(Res.string.common_settings_cloud_schedule)) {
            SwitchRow(
                label = stringResource(Res.string.common_settings_cloud_auto),
                help = stringResource(Res.string.common_settings_cloud_auto_help),
                checked = config.enabled,
                // Turning it on needs a working engine; turning it off must always work.
                enabled = config.enabled || available,
                onCheckedChange = actions::setCloudAutomatic,
            )
            Dropdown(
                selected = intervalLabel(config.intervalMinutes),
                options = INTERVAL_OPTIONS,
                optionLabel = { intervalLabel(it) },
                onSelect = actions::setCloudInterval,
                label = stringResource(Res.string.common_settings_cloud_interval),
            )
        }
        SettingsSection(title = stringResource(Res.string.common_settings_cloud_status)) {
            SyncStatusSection(cloud, available, actions::syncNow)
        }
    }
}

/**
 * "Add another device", and the guide it opens ([AddDeviceGuideDialog]): where the library's
 * settings mention using it on more than one device.
 */
@Composable
internal fun AddDeviceAction(state: SettingsPaneState) {
    var showGuide by rememberSaveable { mutableStateOf(false) }
    SettingsActionRow(
        label = stringResource(Res.string.onboarding_add_device),
        onClick = { showGuide = true },
        help = stringResource(Res.string.onboarding_add_device_help),
    )
    if (showGuide) {
        val config = state.cloud.config
        AddDeviceGuideDialog(
            cloudFolder = config.remote?.takeIf { config.configured }?.let(::cloudFolderLabel),
            libraryLocation = state.settings.snapshot.libraryLocation,
            onDismiss = { showGuide = false },
        )
    }
}

/**
 * The cloud folder the library mirrors to, as the user knows it ([cloudFolderLabel]: provider and
 * folders, not the rclone address the wizard stored), with the two ways to change it.
 * Disconnecting asks first, as it forgets the sign-in.
 */
@Composable
private fun ConnectedRemote(
    remote: String,
    setup: CloudSetupActions,
    available: Boolean,
    editable: Boolean,
) {
    var confirmDisconnect by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(ItemSpacing)) {
        Text(text = cloudFolderLabel(remote), style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(ItemSpacing)) {
            OutlinedButton(onClick = setup::changeFolder, enabled = available && editable) {
                Text(text = stringResource(Res.string.common_settings_cloud_change_folder))
            }
            OutlinedButton(onClick = { confirmDisconnect = true }, enabled = editable) {
                Text(text = stringResource(Res.string.common_settings_cloud_disconnect))
            }
        }
    }
    if (confirmDisconnect) {
        ConfirmDialog(
            title = stringResource(Res.string.common_settings_cloud_disconnect_title),
            message = stringResource(Res.string.common_settings_cloud_disconnect_message),
            confirmLabel = stringResource(Res.string.common_settings_cloud_disconnect),
            destructive = true,
            onConfirm = {
                confirmDisconnect = false
                setup.disconnect()
            },
            onDismiss = { confirmDisconnect = false },
        )
    }
}

/**
 * The sync engine's state, only once it is missing or failed: while it works (or is still being
 * asked), its version and path are nothing the user needs, and the status below says how syncing
 * goes.
 */
@Composable
private fun EngineStatus(
    transport: TransportAvailability?,
    onCheck: () -> Unit,
) {
    if (transport == null || transport is TransportAvailability.Available) return
    Text(text = engineText(transport), style = MaterialTheme.typography.bodyMedium)
    SettingsActionRow(label = stringResource(Res.string.common_settings_cloud_engine_check), onClick = onCheck)
}

@Composable
private fun engineText(transport: TransportAvailability): String =
    when (transport) {
        is TransportAvailability.Available -> {
            stringResource(Res.string.common_settings_cloud_engine_available, transport.version)
        }

        is TransportAvailability.NotInstalled -> {
            if (transport.lookedFor.isBlank()) {
                stringResource(Res.string.common_settings_cloud_engine_none)
            } else {
                stringResource(Res.string.common_settings_cloud_engine_missing, transport.lookedFor)
            }
        }

        is TransportAvailability.Failed -> {
            val detail = transport.detail.orEmpty()
            when (transport.problem) {
                EngineProblem.START_FAILED -> {
                    stringResource(
                        Res.string.common_settings_cloud_engine_start_failed,
                        detail,
                    )
                }

                EngineProblem.EXITED -> {
                    stringResource(Res.string.common_settings_cloud_engine_exited, detail)
                }

                EngineProblem.NOT_READY -> {
                    stringResource(Res.string.common_settings_cloud_engine_not_ready, detail)
                }

                EngineProblem.UNREACHABLE -> {
                    stringResource(Res.string.common_settings_cloud_engine_unreachable, detail)
                }

                EngineProblem.CLOSED -> {
                    stringResource(Res.string.common_settings_cloud_engine_closed)
                }

                EngineProblem.ERROR -> {
                    stringResource(Res.string.common_settings_cloud_engine_failed, detail)
                }
            }
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
    Column(verticalArrangement = Arrangement.spacedBy(ItemSpacing)) {
        if (lastRun == null) {
            HelpText(stringResource(Res.string.common_settings_cloud_never))
            return@Column
        }
        val finishedAt = formatEpochDateTime(lastRun.finishedAt)
        val report = lastRun.report
        val failure = lastRun.failure
        if (failure != null || report == null) {
            HelpText(stringResource(Res.string.common_settings_cloud_last_failed, finishedAt))
            HelpText(failureText(failure ?: SyncFailure.UNKNOWN, lastRun.failureDetail))
            return@Column
        }
        HelpText(
            stringResource(
                Res.string.common_settings_cloud_last_summary,
                finishedAt,
                report.downloadedDocuments + report.downloadedObjects,
                report.uploadedDocuments + report.uploadedObjects,
                report.movedRemote,
                report.deletedRemote,
            ),
        )
        val conflicts = report.conflicts.size
        ProblemList(
            pluralStringResource(Res.plurals.common_settings_cloud_conflict_count, conflicts, conflicts),
            report.conflicts.map { conflictText(it) },
        )
        val errors = report.errors.size
        ProblemList(
            pluralStringResource(Res.plurals.common_settings_cloud_error_count, errors, errors),
            report.errors.map { stringResource(Res.string.common_settings_cloud_problem_line, it.path, it.message) },
        )
    }
}

/** A count of [problems] and the first few of them; nothing when there are none. */
@Composable
private fun ProblemList(
    heading: String,
    problems: List<String>,
) {
    if (problems.isEmpty()) return
    Column {
        HelpText(heading)
        for (problem in problems.take(MAX_LISTED_PROBLEMS)) {
            Text(text = problem, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun conflictText(conflict: SyncConflict): String {
    val path = conflict.remotePath
    val other = conflict.otherPath.orEmpty()
    return when (conflict.kind) {
        ConflictKind.TARGET_OCCUPIED -> {
            stringResource(Res.string.common_settings_cloud_conflict_target_occupied, path)
        }

        ConflictKind.UPLOADED_AS_COPY -> {
            stringResource(
                Res.string.common_settings_cloud_conflict_uploaded_as_copy,
                path,
                other,
            )
        }

        ConflictKind.INSTALLED_AS_COPY -> {
            stringResource(
                Res.string.common_settings_cloud_conflict_installed_as_copy,
                path,
                other,
            )
        }

        ConflictKind.UNIDENTIFIED -> {
            stringResource(Res.string.common_settings_cloud_conflict_unidentified, path)
        }

        ConflictKind.REMOTE_CHANGED -> {
            stringResource(Res.string.common_settings_cloud_conflict_remote_changed, path)
        }

        ConflictKind.LOCAL_CHANGED -> {
            stringResource(Res.string.common_settings_cloud_conflict_local_changed, path)
        }

        ConflictKind.KEPT_ON_THIS_DEVICE -> {
            stringResource(
                Res.string.common_settings_cloud_conflict_kept_on_device,
                path,
            )
        }
    }
}

/** Why a pass failed, as a whole sentence; only an unknown failure quotes the engine's own text. */
@Composable
internal fun failureText(
    failure: SyncFailure,
    detail: String?,
): String =
    when (failure) {
        SyncFailure.REMOTE_UNREACHABLE -> stringResource(Res.string.common_settings_cloud_failure_unreachable)
        SyncFailure.SIGN_IN_EXPIRED -> stringResource(Res.string.common_settings_cloud_failure_sign_in)
        SyncFailure.REMOTE_FULL -> stringResource(Res.string.common_settings_cloud_failure_full)
        SyncFailure.NOT_A_LIBRARY -> stringResource(Res.string.common_settings_cloud_failure_not_library)
        SyncFailure.OTHER_LIBRARY -> stringResource(Res.string.common_settings_cloud_failure_other_library)
        SyncFailure.JOURNAL_BLOCKED -> stringResource(Res.string.common_settings_cloud_failure_journal_blocked)
        SyncFailure.UNKNOWN -> stringResource(Res.string.common_settings_cloud_failure_unknown, detail.orEmpty())
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
internal fun progressLabel(progress: SyncProgress): String {
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
        is SyncOutcome.Failed -> failureText(outcome.reason, outcome.detail)
        SyncOutcome.NotConfigured -> stringResource(Res.string.common_settings_cloud_outcome_not_configured)
        is SyncOutcome.Unavailable -> stringResource(Res.string.common_settings_cloud_outcome_unavailable)
        SyncOutcome.AlreadyRunning -> stringResource(Res.string.common_settings_cloud_outcome_running)
    }
