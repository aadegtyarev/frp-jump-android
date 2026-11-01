package ru.adsrv.frpjump.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
        combine(rows, app.profiles.restorePending, app.profiles.restoreWaiting, app.profiles.disconnectAllFailed) { s, pending, waiting, failed ->
            s.copy(restoring = pending, restoreWaiting = waiting, disconnectAllFailed = failed)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())

    private fun uiState(profiles: ProfilesState, parts: List<ProfilePart>) = ConnectionsUiState(
        groups = groupByDevice(parts.flatMap { it.rows }, parts.associate { it.profile to it.devices }),
        colors = profiles.index.profiles.associate { it.id to it.color },
        sshMethods = parts.fold(emptyMap()) { all, part -> all + part.sshMethods },
        loaded = profiles.loaded,
    )

    /**
     * The tab is on screen: the device lists of the profiles it shows are
     * asked once, so the online dots and the offline state are as of now
     * (only the Devices list keeps its profile's list polled).
     */
    fun shown() {
        app.profiles.state.value.runtimes.values
            .filter { r -> r.connections.connections.value.isNotEmpty() || r.tunnels.state.value.tunnels.isNotEmpty() }
            .forEach { r -> viewModelScope.launch { r.devices.refresh() } }
    }

    /** A profile's connections, for the sheet's name check. */
    fun connections(profile: String): List<Connection> = app.profiles.runtime(profile)?.connections?.connections?.value.orEmpty()
}
