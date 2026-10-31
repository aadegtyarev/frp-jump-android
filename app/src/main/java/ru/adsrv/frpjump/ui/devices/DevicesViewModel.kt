package ru.adsrv.frpjump.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.devices.DeviceDirectory
import ru.adsrv.frpjump.devices.DeviceList
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.devices.tunnelPorts
import ru.adsrv.frpjump.devices.tunnelsFirst
import ru.adsrv.frpjump.session.Notice
import ru.adsrv.frpjump.ui.connections.ConnectionActions
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
    /** Ports this phone has tunnels open to, by device; those devices are listed first. */
    val tunnelPorts: Map<String, List<Int>> = emptyMap(),
)

/**
 * The current profile's devices; switching profiles shows the other
 * profile's list at once (its cached one until its server answers). The
 * list itself is polled by the profile ([ru.adsrv.frpjump.session.Poller])
 * while it is shown.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DevicesViewModel(private val app: FrpJumpApp) : ViewModel() {
    private val directory: StateFlow<DeviceDirectory?> = app.profiles.state.map { it.current?.devices }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, app.profiles.current?.devices)
    private val refreshing = MutableStateFlow(false)

    /** The bar's "disconnect all" for the current profile. */
    val actions = ConnectionActions(app, viewModelScope)

    /** The list is on screen. */
    private var shown = false

    val state: StateFlow<DevicesUiState> =
        combine(listAndTunnels(), refreshing) { (list, ports), busy ->
            DevicesUiState(
                devices = list.devices?.let { tunnelsFirst(it, ports) },
                refreshing = busy,
                error = list.error,
                // A list restored at start is not a failure yet: no banner until a fetch fails.
                staleSince = list.updatedAt.takeIf { list.stale && list.error != null },
                unreachable = list.unreachable,
                tunnelPorts = ports,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, DevicesUiState())

    /** The current profile's list with its own tunnels' ports, so a switch never mixes two profiles. */
    private fun listAndTunnels() = app.profiles.state.map { it.current }.distinctUntilChanged().flatMapLatest { runtime ->
        runtime?.let { r -> combine(r.devices.state, r.tunnels.state) { list, tunnels -> list to tunnelPorts(tunnels.tunnels) } }
            ?: flowOf(DeviceList() to emptyMap())
    }

    /** A message left by the screen before (e.g. after "Forget profile"); shown once. */
    val notice: StateFlow<Notice?> = app.session.notice

    /** The pending notice if it is meant for this list, the current profile's; once. */
    fun takeNotice(): String? = app.profiles.state.value.index.current?.let(app.session::takeNotice)

    init {
        // A switch hands "wanted" over to the profile now shown.
        viewModelScope.launch {
            var previous: DeviceDirectory? = null
            directory.collect { now ->
                // A pull of the profile left behind no longer spins on this one's list.
                if (previous != null && previous != now) refreshing.value = false
                previous?.wanted = false
                previous = now
                if (shown && now != null) want(now)
            }
        }
    }

    /** The list is on screen (or not): the poller fetches it only then. */
    fun shown(visible: Boolean) {
        shown = visible
        val now = directory.value ?: return
        if (visible) want(now) else now.wanted = false
    }

    private fun want(devices: DeviceDirectory) {
        devices.wanted = true
        viewModelScope.launch { devices.refresh() }
    }

    /** Pull to refresh: waits for a fetch already in flight rather than skipping. */
    fun pull() {
        val devices = directory.value ?: return
        refreshing.value = true
        viewModelScope.launch {
            devices.refresh(wait = true)
            refreshing.value = false
        }
    }
}
