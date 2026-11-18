package ru.adsrv.frpjump.assistant

import kotlinx.coroutines.flow.StateFlow
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.ops.Operation
import ru.adsrv.frpjump.ops.tunnel.SavedTunnel
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelHost
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.ops.tunnel.StartSavedTunnel
import ru.adsrv.frpjump.ops.tunnel.StopSavedTunnel
import ru.adsrv.frpjump.ops.tunnel.StopTunnelInput
import ru.adsrv.frpjump.ops.tunnel.TunnelStarted
import ru.adsrv.frpjump.ops.tunnel.TunnelStopped
import ru.adsrv.frpjump.ops.tunnel.TunnelDownTarget
import ru.adsrv.frpjump.ops.tunnel.TunnelUpInput
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.ports.profilePortKey
import ru.adsrv.frpjump.profiles.ProfilesState
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.undo.PendingUndo
import ru.adsrv.frpjump.undo.TunnelDisconnects

/**
 * The app's saved tunnels as [SavedTunnelHost]: lookups of saved state, no I/O. The same hidden set as the Connections list: a tunnel or
 * profile hidden by a pending Undo of the owner's is not found.
 */
class AppSavedTunnels(
    private val profiles: StateFlow<ProfilesState>,
    private val hidden: StateFlow<Set<String>>,
    private val disconnects: TunnelDisconnects,
    /** The sessions shown now, terminals and MQTT: what stopping a tunnel closes (Files screens stay open and show the tunnel down). */
    private val sessionsNow: () -> List<SessionOn>,
    /** What the owner sees a saved tunnel called: its own name, else its default one. */
    private val label: (Connection) -> String,
    /** The tunnels of a profile as its manager lists them now; null when the profile has none to ask. */
    private val tunnelsOf: (String) -> TunnelsState? = { profile -> profiles.value.runtimes[profile]?.tunnels?.state?.value },
) : SavedTunnelHost {
    override fun find(ids: SavedTunnelIds): SavedTunnel? {
        val state = profiles.value
        val profile = state.index.profiles.singleOrNull { it.id == ids.profileId } ?: return null
        val runtime = state.runtimes[ids.profileId] ?: return null
        if (!state.loaded || profile.activationPending || runtime.session.removed.value) return null
        val connection = runtime.connections.connections.value.singleOrNull { it.id == ids.connectionId } ?: return null
        val now = hidden.value
        if (PendingUndo.connectionKey(ids.profileId, connection.id) in now) return null
        return SavedTunnel(profile.id, profile.name, connection.id, label(connection), connection.device, connection.port, connection.kind)
    }

    override fun isStopping(tunnel: SavedTunnel): Boolean =
        profilePortKey(tunnel.profileId, tunnel.device, tunnel.port) in PendingUndo.darkTunnels(hidden.value) || disconnects.disconnecting(tunnel.ref)

    override fun isOn(tunnel: SavedTunnel): Boolean =
        tunnelsOf(tunnel.profileId)?.tunnels?.any { it.key == localPortKey(tunnel.device, tunnel.port) } == true && !isStopping(tunnel)

    override fun pin(ids: SavedTunnelIds): Any? {
        val tunnel = find(ids) ?: return null
        val state = profiles.value
        val running = if (isOn(tunnel)) {
            tunnelsOf(tunnel.profileId)?.tunnels?.singleOrNull { it.key == localPortKey(tunnel.device, tunnel.port) }?.let { listOf(it.grantId, it.localPort) }
        } else null
        val connection = state.runtimes[ids.profileId]?.connections?.connections?.value?.singleOrNull { it.id == ids.connectionId }
        return listOf(state.index.profiles.singleOrNull { it.id == ids.profileId }, connection?.withoutFavorite, state.enrollments[ids.profileId], running)
    }

    override fun tabs(tunnel: SavedTunnel): Int = sessionsNow().count { it.tunnel == tunnel.ref }
}

/**
 * The assistant's two tunnel operations over the screens' own [up] ([ru.adsrv.frpjump.ops.tunnel.TunnelUp]) and [down]
 * ([ru.adsrv.frpjump.ops.tunnel.TunnelDown]) and the saved tunnels of [host]; and the pure lookups of the confirmation card.
 */
class AssistantTunnels(
    private val host: SavedTunnelHost,
    private val up: Operation<TunnelUpInput, Int>,
    down: Operation<TunnelDownTarget, Unit>,
) {
    /** The start, waiting at most [waitMs] in all (the assistant's tool time limit leaves room to say how it ended). */
    internal fun start(waitMs: Long): Operation<SavedTunnelIds, TunnelStarted> = StartSavedTunnel(host, up, waitMs)
    internal val stop: Operation<StopTunnelInput, TunnelStopped> by lazy { StopSavedTunnel(host, down) }

    /** What a stop of this tunnel is pinned to ([SavedTunnelHost.pin]). */
    internal fun pin(ids: SavedTunnelIds): Any? = host.pin(ids)

    /** The saved tunnel, or null when the profile or tunnel is unknown, gone or hidden. */
    internal fun find(ids: SavedTunnelIds): SavedTunnel? = host.find(ids)

    /** Whether the saved tunnel is on as the owner sees it. */
    internal fun isOn(tunnel: SavedTunnel): Boolean = host.isOn(tunnel)

    /** What the card of a stop shows: the tunnel as the owner sees it and its tabs; a pure lookup. Null when the tunnel is unknown. */
    internal fun describe(ids: SavedTunnelIds): StopCard? = host.find(ids)?.let { StopCard(it.profileName, it.name, it.device, it.port, host.tabs(it)) }
}

/** What the card of a stop shows: the tunnel as the owner sees it, and how many tabs of it close. Display text; not a data class: no names in toString. */
class StopCard(val profile: String, val name: String, val device: String, val port: Int, val tabs: Int) {
    override fun toString() = "StopCard(tabs=$tabs)"
}
