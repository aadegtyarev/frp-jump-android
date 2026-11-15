package ru.adsrv.frpjump.ops.tunnel

import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation
import ru.adsrv.frpjump.ops.TunnelFailure
import ru.adsrv.frpjump.ops.TunnelToolFailure
import ru.adsrv.frpjump.ops.TunnelToolRefusal
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.settings.GrantTarget
import ru.adsrv.frpjump.tunnel.TunnelRef

/** A saved tunnel (a saved connection of a profile) as the assistant's tunnel tools name it. [name] is what the owner sees. */
data class SavedTunnel(
    val profileId: String, val profileName: String, val connectionId: String, val name: String,
    val device: String, val port: Int, val kind: PortKind,
) {
    val ref get() = TunnelRef(profileId, device, port)
}

/** The ids the tunnel tools are called with (`profile_information`'s `profile.id` and `connection.id`). */
data class SavedTunnelIds(val profileId: String, val connectionId: String)

/** What the tunnel tools need of the app: lookups of saved state, no I/O. */
interface SavedTunnelHost {
    /** The saved tunnel; null when the profile or the tunnel is unknown, gone or hidden (a pending Undo of the owner's). */
    fun find(ids: SavedTunnelIds): SavedTunnel?
    /** Whether the tunnel is on as the owner sees it: up, not dark (its Undo pending) and not going down. */
    fun isOn(tunnel: SavedTunnel): Boolean
    /**
     * Equal while the saved tunnel, its profile and, when it is on, that very instance of the tunnel are unchanged; null when the saved
     * tunnel is unknown. It differs once the tunnel is removed, edited, restarted or stopping.
     */
    fun pin(ids: SavedTunnelIds): Any?
    /** How many terminal tabs shown go through the tunnel: what stopping it closes. */
    fun tabs(tunnel: SavedTunnel): Int
}

/** What [StartSavedTunnel] says: the tunnel's name and whether it was on before. */
data class TunnelStarted(val name: String, val wasOn: Boolean)

/** What [StopSavedTunnel] says. */
data class TunnelStopped(val name: String)

/**
 * The owner's Start for a saved tunnel, for the assistant: [TunnelUp] (the screens' own operation: brings the tunnel up when it is down
 * and waits until the device takes connections on it) over the saved tunnel named by ids. Changes only this app, nothing on the device.
 */
class StartSavedTunnel(
    private val host: SavedTunnelHost,
    private val up: Operation<TunnelUpInput, Int>,
) : Operation<SavedTunnelIds, TunnelStarted> {
    override val name = "start_tunnel"
    override val effect = Effect.CHANGE_LOCAL
    override fun target(input: SavedTunnelIds) = GrantTarget(input.profileId, input.connectionId)

    override suspend fun invoke(input: SavedTunnelIds): OpResult<TunnelStarted> {
        val tunnel = host.find(input) ?: return OpResult.Refused(TunnelToolRefusal.Unknown)
        val wasOn = host.isOn(tunnel)
        return when (val result = up(TunnelUpInput(tunnel.profileId, tunnel.device, tunnel.port, tunnel.kind))) {
            is OpResult.Done -> OpResult.Done(TunnelStarted(tunnel.name, wasOn))
            is OpResult.Refused -> OpResult.Refused(TunnelToolRefusal.Unknown)
            is OpResult.Failed -> OpResult.Failed(
                if (result.reason is TunnelFailure.DeviceNotListening) TunnelToolFailure.NoConnections(tunnel.name) else TunnelToolFailure.DidNotComeUp(tunnel.name),
                result.outcome,
            )
        }
    }
}

/** What a stop is asked to do: the saved tunnel, and the [pin] it had when the owner was asked ([SavedTunnelHost.pin]). */
data class StopTunnelInput(val ids: SavedTunnelIds, val pin: Any?)

/**
 * The owner's Stop for a saved tunnel, for the assistant, through [TunnelDown] (the screens' own operation) with no Undo: the owner's
 * answer on the card is the check. Acts only on the tunnel that was described: when it changed since, nothing is stopped. Its terminal
 * tabs close with it (the sessions follow the tunnel); tmux sessions on the device stay.
 */
class StopSavedTunnel(
    private val host: SavedTunnelHost,
    private val down: Operation<TunnelDownTarget, Unit>,
) : Operation<StopTunnelInput, TunnelStopped> {
    override val name = "stop_tunnel"
    override val effect = Effect.CHANGE_LOCAL
    override fun target(input: StopTunnelInput) = GrantTarget(input.ids.profileId, input.ids.connectionId)

    override suspend fun invoke(input: StopTunnelInput): OpResult<TunnelStopped> {
        val tunnel = host.find(input.ids) ?: return OpResult.Refused(TunnelToolRefusal.Unknown)
        // Not the tunnel the owner was asked about: removed, edited, restarted or already stopping since. Nothing is stopped.
        if (host.pin(input.ids) != input.pin) return OpResult.Refused(TunnelToolRefusal.Changed)
        if (!host.isOn(tunnel)) return OpResult.Refused(TunnelToolRefusal.NotOn(tunnel.name))
        return when (val result = down(TunnelDownTarget.OneNow(tunnel.ref))) {
            is OpResult.Done -> OpResult.Done(TunnelStopped(tunnel.name))
            is OpResult.Refused -> OpResult.Refused(TunnelToolRefusal.Unknown)
            is OpResult.Failed -> result
        }
    }
}
