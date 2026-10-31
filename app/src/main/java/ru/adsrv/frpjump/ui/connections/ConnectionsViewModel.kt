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
import ru.adsrv.frpjump.FrpJumpApp
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.activeFirst
import ru.adsrv.frpjump.connections.connectionRows
import ru.adsrv.frpjump.profiles.ProfilesState
import ru.adsrv.frpjump.tunnel.Waiting
import ru.adsrv.frpjump.undo.PendingUndo

data class ConnectionsUiState(
    val active: List<ConnectionRow> = emptyList(),
    val saved: List<ConnectionRow> = emptyList(),
    /** Profile colour names by profile id. */
    val colors: Map<String, String> = emptyMap(),
    /** False until the profiles' lists are read: no "nothing saved" before that. */
    val loaded: Boolean = false,
    /** Tunnels still to come back after a restart; null while not known. */
    val restoring: Int? = 0,
    val restoreWaiting: Waiting? = null,
    /** The last "Disconnect all" could not tell a server: tunnels may come back. */
    val disconnectAllFailed: Boolean = false,
)

/** Every profile's connections, active ones first, each group in profile order. */
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
                    combine(r.connections.connections, r.tunnels.state, app.pendingUndo.hidden) { c, t, hidden ->
                        connectionRows(r.id, c, t.withoutDark(PendingUndo.darkTunnels(hidden, r.id)), hidden = PendingUndo.connectionIds(hidden, r.id))
                    }
                },
            ) { lists ->
                uiState(profiles, lists.flatMap { it })
            }
        }
    }.let { rows ->
        combine(rows, app.profiles.restorePending, app.profiles.restoreWaiting, app.profiles.disconnectAllFailed) { s, pending, waiting, failed ->
            s.copy(restoring = pending, restoreWaiting = waiting, disconnectAllFailed = failed)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())

    private fun uiState(profiles: ProfilesState, rows: List<ConnectionRow>): ConnectionsUiState {
        val (active, saved) = activeFirst(rows)
        return ConnectionsUiState(active, saved, profiles.index.profiles.associate { it.id to it.color }, loaded = profiles.loaded)
    }

    /** A profile's connections, for the sheet's name check. */
    fun connections(profile: String): List<Connection> = app.profiles.runtime(profile)?.connections?.connections?.value.orEmpty()
}
