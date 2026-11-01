package ru.adsrv.frpjump.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.DeviceGroup
import ru.adsrv.frpjump.connections.connectionRows
import ru.adsrv.frpjump.connections.groupByDevice
import ru.adsrv.frpjump.devices.DeviceDirectory
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.ProfilesState
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.tunnel.Waiting
import ru.adsrv.frpjump.undo.PendingUndo

data class ConnectionsUiState(
    /** By device: active ones first, then online, then the rest ([groupByDevice]). */
    val groups: List<DeviceGroup> = emptyList(),
    /** Profile colour names by profile id. */
    val colors: Map<String, String> = emptyMap(),
    /** The login method remembered for each SSH row, by [ConnectionRow.key], for the cards' details. */
    val sshMethods: Map<String, SshAuthMethod> = emptyMap(),
    /** False until the profiles' lists are read: no "nothing saved" before that. */
    val loaded: Boolean = false,
    /** Tunnels still to come back after a restart; null while not known. */
    val restoring: Int? = 0,
    val restoreWaiting: Waiting? = null,
    /** The last "Disconnect all" could not tell a server: tunnels may come back. */
    val disconnectAllFailed: Boolean = false,
    /** Every profile's device list is read (cached or fresh): the groups' order means something. */
    val listsRead: Boolean = false,
    /** A pull to refresh is asking the servers. */
    val refreshing: Boolean = false,
) {
    val rows: List<ConnectionRow> get() = groups.flatMap { it.rows }

    /** The groups come from more than one profile: each header shows its profile's colour. */
    val manyProfiles: Boolean get() = groups.map { it.profile }.distinct().size > 1
}

/** One profile's part of the tab. */
private class ProfilePart(
    val profile: String,
    val rows: List<ConnectionRow>,
    val devices: List<DeviceRow>?,
    val sshMethods: Map<String, SshAuthMethod>,
)

/** Every profile's connections, grouped by device. */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsViewModel(private val app: FrpJumpApp) : ViewModel() {
    val actions = ConnectionActions(app, viewModelScope)

    private val refreshing = MutableStateFlow(false)

    val state: StateFlow<ConnectionsUiState> = app.profiles.state.flatMapLatest { profiles ->
        val runtimes = profiles.index.profiles.mapNotNull { p -> profiles.runtimes[p.id] }
        if (runtimes.isEmpty()) {
            flowOf(uiState(profiles, emptyList()))
        } else {
            combine(
                runtimes.map { r ->
                    // A connection deleted while its Undo is on screen is not listed; a tunnel disconnected so shows down.
                    // "Use key login" changes a method: the details follow.
                    combine(r.connections.connections, r.tunnels.state, app.pendingUndo.hidden, r.devices.state, r.sshLogins.changes) { c, t, hidden, list, _ ->
                        val rows = connectionRows(r.id, c, t.withoutDark(PendingUndo.darkTunnels(hidden, r.id)), hidden = PendingUndo.connectionIds(hidden, r.id))
                        val methods = rows.filter { it.saved && it.connection.kind == PortKind.SSH }
                            .associate { it.key to r.sshLogins.get(it.connection.device, it.connection.port).method }
                        ProfilePart(r.id, rows, list.devices, methods)
                    }
                },
            ) { parts ->
                uiState(profiles, parts.toList())
            }
        }
    }.let { rows ->
        combine(rows, app.profiles.restorePending, app.profiles.restoreWaiting, app.profiles.disconnectAllFailed, refreshing) { s, pending, waiting, failed, busy ->
            s.copy(restoring = pending, restoreWaiting = waiting, disconnectAllFailed = failed, refreshing = busy)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())

    private fun uiState(profiles: ProfilesState, parts: List<ProfilePart>) = ConnectionsUiState(
        groups = groupByDevice(parts.flatMap { it.rows }, parts.associate { it.profile to it.devices }),
        colors = profiles.index.profiles.associate { it.id to it.color },
        sshMethods = parts.fold(emptyMap()) { all, part -> all + part.sshMethods },
        loaded = profiles.loaded,
        listsRead = parts.all { it.devices != null },
    )

    /** The tab is on screen. */
    private var visible = false

    /** The device lists this tab has asked the pollers to keep fresh. */
    private var wanted: Set<DeviceDirectory> = emptySet()

    init {
        // A profile added or forgotten while the tab shows: its list is wanted, or let go.
        viewModelScope.launch { app.profiles.state.collect { if (visible) wantLists() } }
    }

    /**
     * The tab is on screen (or not). While it is, every profile's device list
     * is wanted as on Devices, so the dots and the offline state follow the
     * server (the poller fetches the shown profile's; the others are asked
     * once here, each time the tab shows).
     */
    fun shown(on: Boolean) {
        visible = on
        if (!on) return letGo()
        wantLists()
        runtimes().forEach { r -> viewModelScope.launch { r.devices.refresh() } }
    }

    /** Pull to refresh: every profile's list, waiting for a fetch already in flight. */
    fun pull() {
        refreshing.value = true
        viewModelScope.launch {
            runtimes().map { r -> async { r.devices.refresh(wait = true) } }.awaitAll()
            refreshing.value = false
        }
    }

    private fun runtimes() = app.profiles.state.value.runtimes.values.toList()

    private fun wantLists() {
        val now = runtimes().map { it.devices }.toSet()
        (wanted - now).forEach { it.want(this, false) }
        now.forEach { it.want(this, true) }
        wanted = now
    }

    private fun letGo() {
        wanted.forEach { it.want(this, false) }
        wanted = emptySet()
    }

    override fun onCleared() = letGo()

    /** A profile's connections, for the sheet's name check. */
    fun connections(profile: String): List<Connection> = app.profiles.runtime(profile)?.connections?.connections?.value.orEmpty()
}
