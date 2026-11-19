package ru.adsrv.frpjump.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.DeviceGroup
import ru.adsrv.frpjump.connections.groupByDevice
import ru.adsrv.frpjump.devices.DeviceDirectory
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.tunnel.Waiting

data class ConnectionsUiState(
    /**
     * By device, in the stable order of [groupByDevice].
     */
    val groups: List<DeviceGroup> = emptyList(),
    /** Profile colour names by profile id. */
    val colors: Map<String, String> = emptyMap(),
    val profileNames: Map<String, String> = emptyMap(),
    val tunnelStates: Map<String, ru.adsrv.frpjump.tunnel.TunnelsState> = emptyMap(),
    val devices: List<DeviceGroup> = emptyList(),
    /** The login method remembered for each SSH row, by [ConnectionRow.key], for the cards' details. */
    val sshMethods: Map<String, SshAuthMethod> = emptyMap(),
    /** False until the profiles' lists are read: no "nothing saved" before that. */
    val loaded: Boolean = false,
    /** Tunnels still to come back after a restart; null while not known. */
    val restoring: Int? = 0,
    val restoreWaiting: Waiting? = null,
    /** The last "Disconnect all" could not tell a server: tunnels may come back. */
    val disconnectAllFailed: Boolean = false,
    /** A pull to refresh is asking the servers. */
    val refreshing: Boolean = false,
) {
    val rows: List<ConnectionRow> get() = groups.flatMap { it.rows }

    /** The groups come from more than one profile: each header shows its profile's colour. */
    val manyProfiles: Boolean get() = groups.map { it.profile }.distinct().size > 1
}

/** Every profile's connections, grouped by device. */
class ConnectionsViewModel(private val app: FrpJumpApp) : ViewModel() {
    val actions = ConnectionActions(app, viewModelScope)

    private val refreshing = MutableStateFlow(false)

    /** The tab is on screen. */
    private val visible = MutableStateFlow(false)

    val state: StateFlow<ConnectionsUiState> =
        combine(app.profileInformation.observe().map(::connectionsUiState), app.profiles.restorePending, app.profiles.restoreWaiting, app.profiles.disconnectAllFailed, refreshing) { s, pending, waiting, failed, busy ->
            s.copy(restoring = pending, restoreWaiting = waiting, disconnectAllFailed = failed, refreshing = busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())

    /** The device lists this tab has asked the pollers to keep fresh. */
    private var wanted: Set<DeviceDirectory> = emptySet()

    /** Asks the other profiles' lists again while the tab shows. */
    private var slowLoop: Job? = null

    init {
        // A profile added or forgotten while the tab shows (or read at a cold start): its list is wanted, or let go.
        viewModelScope.launch { app.profiles.state.collect { if (visible.value) wantLists() } }
    }

    /**
     * The tab is on screen (or not). While it is, every profile's device list
     * is wanted as on Devices, so the dots and the offline state follow the
     * server: each is asked when the tab shows, the shown profile's is polled
     * with it, the others are asked again every [OTHER_PROFILES_MS].
     */
    fun shown(on: Boolean) {
        slowLoop?.cancel()
        if (!on) {
            visible.value = false
            return letGo()
        }
        visible.value = true
        wantLists()
        slowLoop = viewModelScope.launch {
            while (true) {
                delay(OTHER_PROFILES_MS)
                runtimes().filter { !it.current.value }.forEach { r -> launch { r.devices.refresh() } }
            }
        }
    }

    /** Pull to refresh: every profile's list, waiting for a fetch already in flight. */
    fun pull() {
        refreshing.value = true
        viewModelScope.launch {
            app.profileInformation.refreshAll()
            refreshing.value = false
        }
    }

    private fun runtimes() = app.profiles.state.value.runtimes.values.toList()

    /** Marks every profile's list wanted; the ones not wanted before are asked now (their fetches). */
    private fun wantLists() {
        val now = runtimes().map { it.devices }.toSet()
        (wanted - now).forEach { it.want(this, false) }
        now.forEach { it.want(this, true) }
        val fresh = now - wanted
        wanted = now
        fresh.forEach { devices -> viewModelScope.launch { devices.refresh() } }
    }

    private fun letGo() {
        wanted.forEach { it.want(this, false) }
        wanted = emptySet()
    }

    override fun onCleared() = letGo()

    /** A profile's connections, for the sheet's name check. */
    fun connections(profile: String): List<Connection> = app.profiles.runtime(profile)?.connections?.connections?.value.orEmpty()
}

/** How often the tab asks the device lists of the profiles not shown (the shown one is polled). */
private const val OTHER_PROFILES_MS = 10_000L

/** Presentation only; inventory and Undo rules belong to the shared API. */
internal fun connectionsUiState(information: ru.adsrv.frpjump.profiles.ProfileInformationState) =
    ConnectionsUiState(
        groups = groupByDevice(information.profiles.flatMap { it.rows }, information.profiles.associate { it.id to it.devices?.devices }),
        colors = information.profiles.associate { it.id to it.color },
        profileNames = information.profiles.associate { it.id to it.name },
        tunnelStates = information.profiles.mapNotNull { profile -> profile.tunnels?.let { profile.id to it } }.toMap(),
        devices = information.profiles.flatMap { profile ->
            val known = profile.devices?.devices.orEmpty().map { device ->
                DeviceGroup(profile.id, device.name, device, emptyList())
            }
            val existing = groupByDevice(profile.rows, mapOf(profile.id to profile.devices?.devices))
            known + existing.filter { group -> known.none { it.name == group.name } }.map { it.copy(rows = emptyList()) }
        },
        sshMethods = information.profiles.fold(emptyMap()) { all, part -> all + part.sshMethods },
        loaded = information.loaded,
    )
