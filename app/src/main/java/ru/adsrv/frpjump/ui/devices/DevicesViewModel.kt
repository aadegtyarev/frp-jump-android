package ru.adsrv.frpjump.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.devices.DeviceRow
import java.time.Instant

data class DevicesUiState(
    /** Null until the first answer or the cached list. */
    val devices: List<DeviceRow>? = null,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** Set once a refresh of an old list failed: when the server last answered. */
    val staleSince: Instant? = null,
    /** The failure is the network, not the server. */
    val unreachable: Boolean = false,
)

/**
 * The current profile's devices. The list itself is polled by the profile
 * ([ru.adsrv.frpjump.session.Poller]) while it is shown.
 */
class DevicesViewModel(app: FrpJumpApp) : ViewModel() {
    private val devices = checkNotNull(app.profiles.current) { "no current profile" }.devices
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<DevicesUiState> = combine(devices.state, refreshing) { list, busy ->
        DevicesUiState(
            devices = list.devices,
            refreshing = busy,
            error = list.error,
            // A list restored at start is not a failure yet: no banner until a fetch fails.
            staleSince = list.updatedAt.takeIf { list.stale && list.error != null },
            unreachable = list.unreachable,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesUiState())

    /** Open SSH sessions, so the list can lead back to them. */
    val sshSessions = app.ssh.sessions

    /** The list is on screen (or not): the poller fetches it only then. */
    fun shown(visible: Boolean) {
        devices.wanted = visible
        if (visible) viewModelScope.launch { devices.refresh() }
    }

    /** Pull to refresh: waits for a fetch already in flight rather than skipping. */
    fun pull() {
        refreshing.value = true
        viewModelScope.launch {
            devices.refresh(wait = true)
            refreshing.value = false
        }
    }
}
