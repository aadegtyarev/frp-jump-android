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
import ru.adsrv.frpjump.tunnel.SessionKind
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
    /** The sessions shown now, terminals and MQTT: what stopping a tunnel closes. */
    private val sessionsNow: () -> List<SessionOn>,
    /** What the owner sees a saved tunnel called: its own name, else its default one. */
    private val label: (Connection) -> String,
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

    override fun isOn(tunnel: SavedTunnel): Boolean {
        val runtime = profiles.value.runtimes[tunnel.profileId] ?: return false
        val up = runtime.tunnels.state.value.tunnels.any { it.key == localPortKey(tunnel.device, tunnel.port) }
        val dark = profilePortKey(tunnel.profileId, tunnel.device, tunnel.port) in PendingUndo.darkTunnels(hidden.value)
        return up && !dark && !disconnects.disconnecting(tunnel.ref)
    }

    override fun pin(ids: SavedTunnelIds): Any? {
        val tunnel = find(ids) ?: return null
        val state = profiles.value
        val running = if (isOn(tunnel)) {
            state.runtimes[tunnel.profileId]?.tunnels?.state?.value?.tunnels?.singleOrNull { it.key == localPortKey(tunnel.device, tunnel.port) }
                ?.let { listOf(it.grantId, it.localPort) }
        } else null
        val connection = state.runtimes[ids.profileId]?.connections?.connections?.value?.singleOrNull { it.id == ids.connectionId }
        return listOf(state.index.profiles.singleOrNull { it.id == ids.profileId }, connection, state.enrollments[ids.profileId], running)
    }

    override fun tabs(tunnel: SavedTunnel): Int = sessionsNow().count { it.kind == SessionKind.TERMINAL && it.tunnel == tunnel.ref }
}

/**
 * The assistant's two tunnel operations over the screens' own [up] ([ru.adsrv.frpjump.ops.tunnel.TunnelUp]) and [down]
 * ([ru.adsrv.frpjump.ops.tunnel.TunnelDown]) and the saved tunnels of [host]; and the pure lookups of the confirmation card.
 */
class AssistantTunnels(
    private val host: SavedTunnelHost,
    up: Operation<TunnelUpInput, Int>,
    down: Operation<TunnelDownTarget, Unit>,
) {
    internal val start: Operation<SavedTunnelIds, TunnelStarted> by lazy { StartSavedTunnel(host, up) }
    internal val stop: Operation<StopTunnelInput, TunnelStopped> by lazy { StopSavedTunnel(host, down) }

    /** What a stop of this tunnel is pinned to ([SavedTunnelHost.pin]). */
    internal fun pin(ids: SavedTunnelIds): Any? = host.pin(ids)

    /** What the card of a stop shows: the tunnel as the owner sees it and its tabs; a pure lookup. Null when the tunnel is unknown. */
    internal fun describe(ids: SavedTunnelIds): StopCard? = host.find(ids)?.let { StopCard(it.profileName, it.name, it.device, it.port, host.tabs(it)) }
}

/** What the card of a stop shows: the tunnel as the owner sees it, and how many tabs of it close. Display text; not a data class: no names in toString. */
class StopCard(val profile: String, val name: String, val device: String, val port: Int, val tabs: Int) {
    override fun toString() = "StopCard(tabs=$tabs)"
}
