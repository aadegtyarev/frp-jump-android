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
    /** Set while the list shown is an old one: when the server last answered. */
    val staleSince: Instant? = null,
)

/** The list itself is polled by the app ([ru.adsrv.frpjump.session.Poller]) while it is shown. */
class DevicesViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<DevicesUiState> = combine(app.devices.state, refreshing) { list, busy ->
        DevicesUiState(
            devices = list.devices,
            refreshing = busy,
            error = list.error,
            staleSince = list.updatedAt.takeIf { list.stale },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesUiState())

    /** Open SSH sessions, so the list can lead back to them. */
    val sshSessions = app.ssh.sessions

    /** The list is on screen (or not): the poller fetches it only then. */
    fun shown(visible: Boolean) {
        app.devices.wanted = visible
        if (visible) viewModelScope.launch { app.devices.refresh() }
    }

    /** Pull to refresh: waits for a fetch already in flight rather than skipping. */
    fun pull() {
        refreshing.value = true
        viewModelScope.launch {
            app.devices.refresh(wait = true)
            refreshing.value = false
        }
    }
}
