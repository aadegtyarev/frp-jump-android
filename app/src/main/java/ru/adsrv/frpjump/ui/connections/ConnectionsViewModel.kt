package ru.adsrv.frpjump.ui.connections

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emptyFlow
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
import ru.adsrv.frpjump.connections.keepOrder
import ru.adsrv.frpjump.devices.DeviceDirectory
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.ProfilesState
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.tunnel.Waiting
import ru.adsrv.frpjump.undo.PendingUndo

data class ConnectionsUiState(
    /**
     * By device: active ones first, then online, then the rest ([groupByDevice]),
     * in the order fixed for the visit ([keepOrder]).
     */
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

    /** The lists were asked once since the tab showed (answered or not): the order can be fixed. */
    private val asked = MutableStateFlow(false)

    /**
     * The devices' order, fixed for a visit once the lists were asked (and
     * after a pull): a device that connects or comes online does not jump
     * under the finger; its dot and buttons follow at once. Null: as sorted.
     */
    private val order = MutableStateFlow<List<String>?>(null)

    /** The tab is on screen. */
    private val visible = MutableStateFlow(false)

    /** As [groupByDevice] sorts them now. */
    private val sorted: StateFlow<ConnectionsUiState> = app.profiles.state.flatMapLatest { profiles ->
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
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())

    val state: StateFlow<ConnectionsUiState> =
        combine(sorted, app.profiles.restorePending, app.profiles.restoreWaiting, app.profiles.disconnectAllFailed, combine(refreshing, order, ::Pair)) { s, pending, waiting, failed, (busy, fixed) ->
            s.copy(groups = keepOrder(s.groups, fixed), restoring = pending, restoreWaiting = waiting, disconnectAllFailed = failed, refreshing = busy)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConnectionsUiState())

    private fun uiState(profiles: ProfilesState, parts: List<ProfilePart>) = ConnectionsUiState(
        groups = groupByDevice(parts.flatMap { it.rows }, parts.associate { it.profile to it.devices }),
        colors = profiles.index.profiles.associate { it.id to it.color },
        sshMethods = parts.fold(emptyMap()) { all, part -> all + part.sshMethods },
        loaded = profiles.loaded,
    )

    /** The device lists this tab has asked the pollers to keep fresh. */
    private var wanted: Set<DeviceDirectory> = emptySet()

    /** Asks the other profiles' lists again while the tab shows. */
    private var slowLoop: Job? = null

    init {
        // A profile added or forgotten while the tab shows (or read at a cold start): its list is wanted, or let go.
        viewModelScope.launch { app.profiles.state.collect { if (visible.value) wantLists() } }
        // Fixes the order once the lists were asked and no pull is under way, while the tab shows.
        viewModelScope.launch {
            visible.flatMapLatest { on -> if (on) combine(sorted, asked, refreshing, ::Triple) else emptyFlow() }
                .collect { (s, done, busy) ->
                    if (done && !busy && order.value == null && s.groups.isNotEmpty()) order.value = s.groups.map { it.key }
                }
        }
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
        // A new visit: sorted again once its lists were asked.
        order.value = null
        asked.value = false
        visible.value = true
        val first = wantLists()
        viewModelScope.launch {
            first.joinAll()
            asked.value = true
        }
        slowLoop = viewModelScope.launch {
            while (true) {
                delay(OTHER_PROFILES_MS)
                runtimes().filter { !it.current.value }.forEach { r -> launch { r.devices.refresh() } }
            }
        }
    }

    /** Pull to refresh: every profile's list, waiting for a fetch already in flight. */
    fun pull() {
        order.value = null
        refreshing.value = true
        viewModelScope.launch {
            runtimes().map { r -> async { r.devices.pull() } }.awaitAll()
            refreshing.value = false
        }
    }

    private fun runtimes() = app.profiles.state.value.runtimes.values.toList()

    /** Marks every profile's list wanted; the ones not wanted before are asked now (their fetches). */
    private fun wantLists(): List<Job> {
        val now = runtimes().map { it.devices }.toSet()
        (wanted - now).forEach { it.want(this, false) }
        now.forEach { it.want(this, true) }
        val fresh = now - wanted
        wanted = now
        return fresh.map { devices -> viewModelScope.launch { devices.refresh() } }
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
