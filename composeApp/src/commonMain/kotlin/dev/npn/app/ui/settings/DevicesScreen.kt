package dev.npn.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.npn.app.ui.common.BackTopBar
import dev.npn.app.ui.common.HelpText
import dev.npn.app.ui.common.SettingsSection
import dev.npn.app.ui.common.formatEpochDateTime
import dev.npn.app.ui.common.rememberSnackbarMessages
import dev.npn.core.app.LibraryDevice
import dev.npn.core.app.LibrarySession
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_devices_device
import no_papers_needed.composeapp.generated.resources.common_devices_empty
import no_papers_needed.composeapp.generated.resources.common_devices_last_heard
import no_papers_needed.composeapp.generated.resources.common_devices_not_yet_heard
import no_papers_needed.composeapp.generated.resources.common_devices_retire
import no_papers_needed.composeapp.generated.resources.common_devices_retired
import no_papers_needed.composeapp.generated.resources.common_devices_silent
import no_papers_needed.composeapp.generated.resources.common_devices_this_device
import no_papers_needed.composeapp.generated.resources.common_devices_unretire
import no_papers_needed.composeapp.generated.resources.common_settings_devices
import no_papers_needed.composeapp.generated.resources.common_settings_devices_help
import org.jetbrains.compose.resources.stringResource

/**
 * The devices that share the library, opened from the Library settings pane: when each last wrote
 * an acknowledgment record, and a Retire for one no longer in use, so the trash purge stops waiting
 * for it.
 */
@Composable
internal fun DevicesScreen(
    session: LibrarySession,
    onBack: () -> Unit,
    vm: DevicesViewModel = viewModel { DevicesViewModel(session) },
) {
    val devices by vm.list.collectAsStateWithLifecycle()
    val snackbar = rememberSnackbarMessages(vm.messages)
    Scaffold(
        topBar = { BackTopBar(title = stringResource(Res.string.common_settings_devices), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        DevicesContent(
            devices = devices,
            actions =
                object : DeviceActions {
                    override fun retire(deviceId: String) = vm.retire(deviceId)

                    override fun unretire(deviceId: String) = vm.unretire(deviceId)
                },
            modifier = Modifier.padding(padding).fillMaxSize(),
        )
    }
}

/** What a device's row can do. */
internal interface DeviceActions {
    fun retire(deviceId: String)

    fun unretire(deviceId: String)
}

@Composable
private fun DevicesContent(
    devices: List<LibraryDevice>?,
    actions: DeviceActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.verticalScroll(rememberScrollState()).padding(ContentPadding),
        verticalArrangement = Arrangement.spacedBy(SectionGap),
    ) {
        HelpText(stringResource(Res.string.common_settings_devices_help))
        val list = devices ?: return@Column
        list.forEach { device -> DeviceRow(device, actions) }
        if (list.none { !it.thisDevice }) HelpText(stringResource(Res.string.common_devices_empty))
    }
}

@Composable
private fun DeviceRow(
    device: LibraryDevice,
    actions: DeviceActions,
) {
    val title =
        if (device.thisDevice) {
            stringResource(Res.string.common_devices_this_device)
        } else {
            stringResource(Res.string.common_devices_device, device.deviceId.take(SHORT_ID))
        }
    SettingsSection(title = title) {
        val heard = device.lastHeardMillis
        Text(
            text =
                if (heard == null) {
                    stringResource(Res.string.common_devices_not_yet_heard)
                } else {
                    stringResource(Res.string.common_devices_last_heard, formatEpochDateTime(heard))
                },
            style = MaterialTheme.typography.bodyMedium,
        )
        when {
            device.retired -> HelpText(stringResource(Res.string.common_devices_retired))
            device.silent -> HelpText(stringResource(Res.string.common_devices_silent))
        }
        if (!device.thisDevice) {
            Column(verticalArrangement = Arrangement.spacedBy(ButtonGap)) {
                if (device.retired) {
                    OutlinedButton(onClick = { actions.unretire(device.deviceId) }) {
                        Text(stringResource(Res.string.common_devices_unretire))
                    }
                } else {
                    OutlinedButton(onClick = { actions.retire(device.deviceId) }) {
                        Text(stringResource(Res.string.common_devices_retire))
                    }
                }
            }
        }
    }
}

/** How much of a device id a row shows: enough to tell devices apart. */
private const val SHORT_ID = 8
private val ContentPadding = 16.dp
private val SectionGap = 16.dp
private val ButtonGap = 8.dp
