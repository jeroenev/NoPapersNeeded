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
import androidx.compose.material3.TextButton
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
import dev.npn.core.sync.EngineProblem
import dev.npn.core.sync.SyncFailure
import dev.npn.core.sync.SyncHealth
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
import no_papers_needed.composeapp.generated.resources.common_settings_cloud_sync_now
import no_papers_needed.composeapp.generated.resources.common_sync_health_attention
import no_papers_needed.composeapp.generated.resources.common_sync_health_healthy
import no_papers_needed.composeapp.generated.resources.common_sync_health_last_success
import no_papers_needed.composeapp.generated.resources.common_sync_health_more
import no_papers_needed.composeapp.generated.resources.common_sync_health_never_checked
import no_papers_needed.composeapp.generated.resources.common_sync_health_never_success
import no_papers_needed.composeapp.generated.resources.common_sync_health_not_yet
import no_papers_needed.composeapp.generated.resources.common_sync_health_open_document
import no_papers_needed.composeapp.generated.resources.common_sync_health_pending
import no_papers_needed.composeapp.generated.resources.common_sync_health_pending_none
import no_papers_needed.composeapp.generated.resources.common_sync_health_retry
import no_papers_needed.composeapp.generated.resources.common_sync_health_streak
import no_papers_needed.composeapp.generated.resources.common_sync_health_title
import no_papers_needed.composeapp.generated.resources.common_sync_health_working
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
        SettingsSection(title = stringResource(Res.string.common_sync_health_title)) {
            SyncHealthSection(cloud, available, actions::syncNow, actions::openDocument)
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
private fun SyncHealthSection(
    cloud: CloudSyncState,
    canSync: Boolean,
    onSyncNow: () -> Unit,
    onOpenDocument: (String) -> Unit,
) {
    val health = cloud.health
    val status = cloud.status
    Column(verticalArrangement = Arrangement.spacedBy(ItemSpacing)) {
        Row(horizontalArrangement = Arrangement.spacedBy(ItemSpacing)) {
            Button(onClick = onSyncNow, enabled = canSync && !status.running) {
                Text(text = stringResource(Res.string.common_settings_cloud_sync_now))
            }
            // The same pass; the word names what a failed one invites.
            if (health.lastFailure != null && !status.running) {
                OutlinedButton(onClick = onSyncNow, enabled = canSync) {
                    Text(text = stringResource(Res.string.common_sync_health_retry))
                }
            }
        }
        Text(text = healthHeadline(health, cloud.transport), style = MaterialTheme.typography.bodyMedium)
        if (status.running) {
            val progress = status.progress
            if (progress != null) {
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
        } else {
            LastRunSummary(status.lastRun)
        }
        HealthDetails(health)
        HealthProblems(health, cloud.conflictDocuments, onOpenDocument)
        val outcome = cloud.outcome
        if (outcome != null && !status.running) {
            HelpText(outcomeLabel(outcome))
        }
    }
}

/**
 * One calm sentence for the top of the card: working, healthy, how many things need attention, or
 * the failure itself. A quiet board on a library that never synced, not an alarm.
 */
@Composable
private fun healthHeadline(
    health: SyncHealth,
    transport: TransportAvailability?,
): String {
    val failure = health.lastFailure
    return when {
        health.running -> {
            stringResource(Res.string.common_sync_health_working)
        }

        !health.configured -> {
            stringResource(Res.string.common_settings_cloud_never)
        }

        // A pass cannot even start without an engine; say that instead of a stale "in sync".
        transport != null && transport !is TransportAvailability.Available -> {
            engineText(transport)
        }

        health.lastPass == null -> {
            stringResource(Res.string.common_sync_health_never_checked)
        }

        failure != null -> {
            failureText(failure, health.lastFailureDetail)
        }

        health.attentionCount > 0 -> {
            pluralStringResource(
                Res.plurals.common_sync_health_attention,
                health.attentionCount,
                health.attentionCount,
            )
        }

        // "In sync" only by the one rule that says so; what is left is work the last pass still owes.
        health.healthy -> {
            stringResource(Res.string.common_sync_health_healthy)
        }

        else -> {
            stringResource(Res.string.common_sync_health_not_yet)
        }
    }
}

/** The last success, the failure streak, and what the last plan left. */
@Composable
private fun HealthDetails(health: SyncHealth) {
    if (!health.configured) return
    health.lastSuccessAt?.let {
        HelpText(stringResource(Res.string.common_sync_health_last_success, formatEpochDateTime(it)))
    } ?: HelpText(stringResource(Res.string.common_sync_health_never_success))
    if (health.consecutiveFailures > 0) {
        HelpText(
            pluralStringResource(
                Res.plurals.common_sync_health_streak,
                health.consecutiveFailures,
                health.consecutiveFailures,
            ),
        )
    }
    // The counts are what the last pass's plan saw, not a live remote reading; the label says so.
    health.pendingAsOf?.let { checkedAt ->
        val pending = health.pendingUploads + health.pendingDownloads
        if (pending == 0) {
            HelpText(stringResource(Res.string.common_sync_health_pending_none, formatEpochDateTime(checkedAt)))
        } else {
            HelpText(
                stringResource(
                    Res.string.common_sync_health_pending,
                    health.pendingUploads,
                    health.pendingDownloads,
                    formatEpochDateTime(checkedAt),
                ),
            )
        }
    }
}

/** Conflicts and per-file errors, the first few of each, with a link to a document that still exists. */
@Composable
private fun HealthProblems(
    health: SyncHealth,
    documents: Map<String, ConflictDocument>,
    onOpenDocument: (String) -> Unit,
) {
    val conflicts = health.conflicts
    val errors = health.errors
    Column(verticalArrangement = Arrangement.spacedBy(ItemSpacing)) {
        if (conflicts.isNotEmpty()) {
            HelpText(
                pluralStringResource(
                    Res.plurals.common_settings_cloud_conflict_count,
                    conflicts.size,
                    conflicts.size,
                ),
            )
            for (conflict in conflicts.take(MAX_LISTED_PROBLEMS)) {
                val docId = conflict.docId
                val document = docId?.let(documents::get)
                Text(
                    text = conflictText(explainConflict(conflict, document)),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (docId != null && document != null) {
                    TextButton(onClick = { onOpenDocument(docId) }) {
                        Text(text = stringResource(Res.string.common_sync_health_open_document))
                    }
                }
            }
            MoreLine(conflicts.size)
        }
        if (errors.isNotEmpty()) {
            HelpText(
                pluralStringResource(
                    Res.plurals.common_settings_cloud_error_count,
                    errors.size,
                    errors.size,
                ),
            )
            for (error in errors.take(MAX_LISTED_PROBLEMS)) {
                Text(
                    text = stringResource(Res.string.common_settings_cloud_problem_line, error.path, error.message),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            MoreLine(errors.size)
        }
    }
}

/** "N more not shown", when a list was cut to [MAX_LISTED_PROBLEMS]. */
@Composable
private fun MoreLine(total: Int) {
    val rest = total - MAX_LISTED_PROBLEMS
    if (rest > 0) {
        Text(
            text = stringResource(Res.string.common_sync_health_more, rest),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LastRunSummary(lastRun: SyncRunSummary?) {
    Column(verticalArrangement = Arrangement.spacedBy(ItemSpacing)) {
        if (lastRun == null) return@Column
        val finishedAt = formatEpochDateTime(lastRun.finishedAt)
        val report = lastRun.report
        val failure = lastRun.failure
        if (failure != null || report == null) {
            HelpText(stringResource(Res.string.common_settings_cloud_last_failed, finishedAt))
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
    }
}

@Composable
private fun conflictText(explanation: ConflictExplanation): String =
    when (explanation) {
        is ConflictExplanation.TargetOccupied -> {
            stringResource(Res.string.common_settings_cloud_conflict_target_occupied, explanation.label)
        }

        is ConflictExplanation.UploadedAsCopy -> {
            stringResource(
                Res.string.common_settings_cloud_conflict_uploaded_as_copy,
                explanation.label,
                explanation.otherPath,
            )
        }

        is ConflictExplanation.InstalledAsCopy -> {
            stringResource(
                Res.string.common_settings_cloud_conflict_installed_as_copy,
                explanation.label,
                explanation.otherPath,
            )
        }

        is ConflictExplanation.Unidentified -> {
            stringResource(Res.string.common_settings_cloud_conflict_unidentified, explanation.label)
        }

        is ConflictExplanation.RemoteChanged -> {
            stringResource(Res.string.common_settings_cloud_conflict_remote_changed, explanation.label)
        }

        is ConflictExplanation.LocalChanged -> {
            stringResource(Res.string.common_settings_cloud_conflict_local_changed, explanation.label)
        }

        is ConflictExplanation.KeptOnThisDevice -> {
            stringResource(
                Res.string.common_settings_cloud_conflict_kept_on_device,
                explanation.label,
            )
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
