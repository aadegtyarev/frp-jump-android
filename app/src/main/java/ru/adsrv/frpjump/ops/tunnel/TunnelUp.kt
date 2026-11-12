package ru.adsrv.frpjump.ops.tunnel

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation
import ru.adsrv.frpjump.ops.RemoteOutcome
import ru.adsrv.frpjump.ops.TunnelFailure
import ru.adsrv.frpjump.ops.TunnelRefusal
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.tunnel.TunnelsState

/** The tunnel to [device]:[port] of [profile], for a connection of [kind] (which names it to the server). */
data class TunnelUpInput(val profile: String, val device: String, val port: Int, val kind: PortKind)

/** What a profile's tunnels offer [TunnelUp]; the tunnel manager's own members. */
interface TunnelUpTunnels {
    val state: StateFlow<TunnelsState>
    fun connectAsync(device: String, port: Int, kind: PortKind)
    /** Null when the device takes connections (or the tunnel is gone, or does not listen: the opener finds out), otherwise why not. */
    suspend fun awaitDevice(device: String, port: Int, fresh: Boolean): String?
}

/** What [TunnelUp] needs of the app: the profile's tunnels and the tunnels whose disconnect waits for Undo. */
interface TunnelUpHost {
    /** Null when the profile is gone. */
    fun tunnels(profile: String): TunnelUpTunnels?
    /** A tunnel whose disconnect waits for Undo, used again: it stays up. False when none waits. */
    fun revive(tunnel: TunnelRef): Boolean
    /** Suspends while the tunnel's disconnect is being done. */
    suspend fun awaitDisconnected(tunnel: TunnelRef)
}

/**
 * The local port of the tunnel to a device port, brought up when it is down and waited for until the device takes connections on it.
 * Used by the screens (Connections, device card, "Use key login"); the assistant does not get it: it does not manage tunnels.
 */
class TunnelUp(
    private val host: TunnelUpHost,
    /** How long a tunnel brought up here is waited for. */
    private val waitMs: Long = TUNNEL_WAIT_MS,
) : Operation<TunnelUpInput, Int> {
    override val name = "tunnel_up"
    override val effect = Effect.CHANGE_LOCAL

    override suspend fun invoke(input: TunnelUpInput): OpResult<Int> {
        val (profile, device, port, kind) = input
        val tunnels = host.tunnels(profile) ?: return OpResult.Refused(TunnelRefusal.ProfileGone)
        val key = localPortKey(device, port)
        fun up(s: TunnelsState) = s.tunnels.firstOrNull { it.key == key }?.localPort
        // Used again while its disconnect waits for Undo: it stays up. Being disconnected: connect afresh after.
        val ref = TunnelRef(profile, device, port)
        if (!host.revive(ref)) host.awaitDisconnected(ref)
        val already = up(tunnels.state.value)
        if (already == null && tunnels.state.value.pending[key] == null) tunnels.connectAsync(device, port, kind)
        val localPort = already ?: withTimeoutOrNull(waitMs) { tunnels.state.mapNotNull { up(it) }.first() }
            // Asked to connect; the server may have granted it.
            ?: return OpResult.Failed(TunnelFailure.DidNotComeUp(tunnels.state.value.errors[key]), RemoteOutcome.UNKNOWN)
        // A tunnel brought up just now may not be open on the device yet: wait for it rather than fail the first connection.
        tunnels.awaitDevice(device, port, fresh = already == null)
            ?.let { return OpResult.Failed(TunnelFailure.DeviceNotListening(it), RemoteOutcome.UNKNOWN) }
        return OpResult.Done(localPort)
    }

    companion object {
        /** How long "Use key login" waits for a tunnel it brought up. */
        const val TUNNEL_WAIT_MS = 45_000L
    }
}
