package dev.npn.app.ui.settings

import androidx.lifecycle.viewModelScope
import dev.npn.app.ui.common.MessagesViewModel
import dev.npn.core.app.LibraryDevice
import dev.npn.core.app.LibrarySession
import dev.npn.core.util.NpnLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no_papers_needed.composeapp.generated.resources.Res
import no_papers_needed.composeapp.generated.resources.common_devices_retire_failed
import no_papers_needed.composeapp.generated.resources.common_devices_unretire_failed

/**
 * The devices page: this device and every other that shares the library and writes acknowledgment
 * records, read when the page opens and again after each retirement it makes.
 */
internal class DevicesViewModel(
    session: LibrarySession,
) : MessagesViewModel() {
    private val devices = session.devices
    override val log: NpnLog = session.log

    private val state = MutableStateFlow<List<LibraryDevice>?>(null)

    /** The devices, this one first; null until read. */
    val list: StateFlow<List<LibraryDevice>?> = state.asStateFlow()

    init {
        refresh()
    }

    /** Retire [deviceId]: the trash purge stops waiting for it, on every device. */
    fun retire(deviceId: String) =
        launchReporting(Res.string.common_devices_retire_failed) {
            devices.retire(deviceId)
            state.value = devices.devices()
        }

    /** Undo [retire]. */
    fun unretire(deviceId: String) =
        launchReporting(Res.string.common_devices_unretire_failed) {
            devices.unretire(deviceId)
            state.value = devices.devices()
        }

    private fun refresh() {
        viewModelScope.launch { state.value = devices.devices() }
    }
}
