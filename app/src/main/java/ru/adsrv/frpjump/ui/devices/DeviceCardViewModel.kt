package ru.adsrv.frpjump.ui.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.connectionRows
import ru.adsrv.frpjump.devices.DeviceList
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.ui.connections.ConnectionActions
import ru.adsrv.frpjump.undo.PendingUndo

data class DeviceCardUiState(
    val rows: List<ConnectionRow> = emptyList(),
    /** The device as the last list said; null when it is not in it. */
    val device: DeviceRow? = null,
    val profileName: String = "",
    val syncError: String? = null,
    val mismatch: String? = null,
    /** The login method remembered per SSH port, for the cards' subtitles. */
    val sshMethods: Map<Int, SshAuthMethod> = emptyMap(),
)

/** [device] of the current profile and its connections. */
class DeviceCardViewModel(private val app: FrpJumpApp, val device: String) : ViewModel() {
    private val runtime = checkNotNull(app.profiles.current) { "no current profile" }

    /** The profile [device] belongs to. */
    val profile: String = runtime.id

    val actions = ConnectionActions(app, viewModelScope)

    val state: StateFlow<DeviceCardUiState> =
        combine(
            // A connection deleted while its Undo is on screen is not listed.
            combine(runtime.connections.connections, app.pendingUndo.hidden) { connections, hidden -> connections to hidden },
            runtime.tunnels.state,
            runtime.devices.state,
            app.profiles.state.map { s -> s.index.find(profile)?.name.orEmpty() },
            // "Use key login" changes a method: the subtitle follows.
            runtime.sshLogins.changes,
        ) { (connections, hidden), tunnels, list, profileName, _ -> uiState(connections, hidden, tunnels, list, profileName) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                // As it is now: the card never flashes "no connections" before the first emission.
                uiState(
                    runtime.connections.connections.value,
                    app.pendingUndo.hidden.value,
                    runtime.tunnels.state.value,
                    runtime.devices.state.value,
                    app.profiles.state.value.index.find(profile)?.name.orEmpty(),
                ),
            )

    private fun uiState(connections: List<Connection>, hidden: Set<String>, tunnels: TunnelsState, list: DeviceList, profileName: String) =
        DeviceCardUiState(
            // Hidden in the rows, not before them: their tunnel must not show as an unsaved one.
            // A tunnel whose disconnect waits for Undo shows down.
            rows = connectionRows(profile, connections, tunnels.withoutDark(PendingUndo.darkTunnels(hidden, profile)), device, hidden = PendingUndo.connectionIds(hidden, profile)),
            device = list.devices?.firstOrNull { it.name == device },
            profileName = profileName,
            syncError = tunnels.syncError,
            mismatch = tunnels.mismatch,
            sshMethods = connections.filter { it.device == device && it.kind == PortKind.SSH }
                .map { it.port }
                .distinct()
                .associateWith { runtime.sshLogins.get(device, it).method },
        )

    /** The profile's connections, for the sheet's name check. */
    fun connections(): List<Connection> = runtime.connections.connections.value
}
