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

data class ConnectionsUiState(
    val active: List<ConnectionRow> = emptyList(),
    val saved: List<ConnectionRow> = emptyList(),
    /** Profile colour names by profile id. */
    val colors: Map<String, String> = emptyMap(),
    /** False until the profiles' lists are read: no "nothing saved" before that. */
    val loaded: Boolean = false,
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
            combine(runtimes.map { r -> combine(r.connections.connections, r.tunnels.state) { c, t -> connectionRows(r.id, c, t) } }) { lists ->
                uiState(profiles, lists.flatMap { it })
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())

    private fun uiState(profiles: ProfilesState, rows: List<ConnectionRow>): ConnectionsUiState {
        val (active, saved) = activeFirst(rows)
        return ConnectionsUiState(active, saved, profiles.index.profiles.associate { it.id to it.color }, loaded = profiles.loaded)
    }

    /** A profile's connections, for the sheet's name check. */
    fun connections(profile: String): List<Connection> = app.profiles.runtime(profile)?.connections?.connections?.value.orEmpty()
}
