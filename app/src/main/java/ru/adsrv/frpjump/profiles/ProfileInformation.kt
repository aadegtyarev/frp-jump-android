package ru.adsrv.frpjump.profiles

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.*
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.connections.connectionRows
import ru.adsrv.frpjump.devices.DeviceList
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.SshAuthMethod
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.undo.PendingUndo

/** Derived application information, not another inventory owner or cache. */
data class ProfileInformationState(
    val loaded: Boolean = false,
    val failure: String? = null,
    val retrying: Boolean = false,
    val current: String? = null,
    val profiles: List<ProfileInformationPart> = emptyList(),
)

data class ProfileInformationPart(
    val id: String,
    val name: String,
    val color: String,
    val activationPending: Boolean,
    val available: Boolean,
    /** Null when the runtime is unavailable; devices may themselves be unknown. */
    val devices: DeviceList? = null,
    val rows: List<ConnectionRow> = emptyList(),
    val tunnels: TunnelsState? = null,
    val sshMethods: Map<String, SshAuthMethod> = emptyMap(),
)

/** The same read/ordinary refresh seam for Devices, Connections and Chat.
 * Collecting does not request data or change screen/poller/runtime lifetimes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProfileInformation(
    private val profiles: StateFlow<ProfilesState>,
    private val hidden: StateFlow<Set<String>>,
) {
    fun observe(): Flow<ProfileInformationState> = profiles.flatMapLatest { state ->
        val parts = state.index.profiles.map { p ->
            val r = state.runtimes[p.id]
            if (r == null) flowOf(ProfileInformationPart(p.id, p.name, p.color, p.activationPending, false))
            else combine(r.connections.connections, r.tunnels.state, hidden, r.devices.state, r.sshLogins.changes) { c, t, undo, list, _ ->
                val part = profileInformationPart(p, c, t, undo, list)
                val rows = part.rows
                val methods = rows.filter { it.saved && it.connection.kind == PortKind.SSH }
                    .associate { it.key to r.sshLogins.get(it.connection.device, it.connection.port).method }
                part.copy(sshMethods = methods)
            }
        }
        fun result(values: List<ProfileInformationPart>) = ProfileInformationState(state.loaded, state.failure, state.retrying, state.index.current, values)
        if (parts.isEmpty()) flowOf(result(emptyList()))
        else combine(parts) { result(it.toList()) }
    }

    /** Same ordinary pull used by Connections: one pull per currently available runtime. */
    suspend fun refreshAll() = coroutineScope {
        profiles.value.runtimes.values.map { r -> async { r.devices.pull() } }.awaitAll()
        Unit
    }
}

/** One composition for saved/unnamed rows and the device list, including pending Undo. */
internal fun profileInformationPart(
    profile: Profile,
    connections: List<ru.adsrv.frpjump.connections.Connection>,
    tunnels: TunnelsState,
    hidden: Set<String>,
    devices: DeviceList,
): ProfileInformationPart {
    val shown = tunnels.withoutDark(PendingUndo.darkTunnels(hidden, profile.id))
    val rows = connectionRows(profile.id, connections, shown, hidden = PendingUndo.connectionIds(hidden, profile.id))
    return ProfileInformationPart(profile.id, profile.name, profile.color, profile.activationPending, true, devices, rows, shown)
}
