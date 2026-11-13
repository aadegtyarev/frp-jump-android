package ru.adsrv.frpjump.ops.tunnel

import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation
import ru.adsrv.frpjump.ops.TunnelRefusal
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.TunnelRef

/** The tunnel to [device]:[port] of [profile], for a connection of [kind] (which names it to the server). */
data class TunnelConnectInput(val profile: String, val device: String, val port: Int, val kind: PortKind)

/** What a profile's tunnels offer [TunnelConnect]: the tunnel manager's own member. */
interface TunnelConnectTunnels {
    fun connectAsync(device: String, port: Int, kind: PortKind)
}

/** What [TunnelConnect] needs of the app: the profile's tunnels and the tunnels whose disconnect waits for Undo or is being done. */
interface TunnelConnectHost {
    /** Null when the profile is gone. */
    fun tunnels(profile: String): TunnelConnectTunnels?
    /** A tunnel whose disconnect waits for Undo, used again: it stays up. False when none waits. */
    fun revive(tunnel: TunnelRef): Boolean
    /** Whether the tunnel's disconnect is being done now (Undo is gone). */
    fun disconnecting(tunnel: TunnelRef): Boolean
    /** Suspends while the tunnel's disconnect is being done. */
    suspend fun awaitDisconnected(tunnel: TunnelRef)
}

/**
 * Connect: the tunnel is asked to connect and the result does not wait for it ([TunnelUp] is the one that waits). A tunnel whose
 * disconnect waits for Undo is revived and only stays up. A tunnel whose disconnect is being done is connected once it is down, not
 * into it. In every other case the connect is asked before the first suspension, so a caller that starts this undispatched on the
 * main thread has asked for it when it returns. Used by the screens only.
 */
class TunnelConnect(private val host: TunnelConnectHost) : Operation<TunnelConnectInput, Unit> {
    override val name = "tunnel_connect"
    override val effect = Effect.CHANGE_LOCAL

    override suspend fun invoke(input: TunnelConnectInput): OpResult<Unit> {
        val (profile, device, port, kind) = input
        val ref = TunnelRef(profile, device, port)
        host.revive(ref)
        val tunnels = host.tunnels(profile) ?: return OpResult.Refused(TunnelRefusal.ProfileGone)
        //MUT await removed
        tunnels.connectAsync(device, port, kind)
        return OpResult.Done(Unit)
    }
}
