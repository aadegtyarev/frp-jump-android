package ru.adsrv.frpjump.assistant

import ru.adsrv.frpjump.errors.DevicePortNotOpen
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.Operation
import ru.adsrv.frpjump.ops.TunnelFailure
import ru.adsrv.frpjump.ops.RemoteOutcome
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.tunnel.SavedTunnel
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelHost
import ru.adsrv.frpjump.ops.tunnel.SavedTunnelIds
import ru.adsrv.frpjump.ops.tunnel.TunnelDownTarget
import ru.adsrv.frpjump.ops.tunnel.TunnelUpInput
import ru.adsrv.frpjump.ports.PortKind

/** Saved tunnels in memory for the tunnel tools' tests: nothing real is started or stopped. */
internal class FakeSavedTunnels : SavedTunnelHost {
    val saved = mutableMapOf<SavedTunnelIds, SavedTunnel>()
    val on = mutableSetOf<SavedTunnelIds>()
    /** Going down: the owner's Stop waits for Undo, or it is being disconnected. Not on while it is. */
    val stopping = mutableSetOf<SavedTunnelIds>()
    /** Bumped to make the pin of a tunnel differ (edited, restarted). */
    val versions = mutableMapOf<SavedTunnelIds, Int>()
    var tabCount = 0

    fun add(profile: String = "p1", id: String = "c1", name: String = "pi ssh", profileName: String = "Home", device: String = "wb7", port: Int = 22, isOn: Boolean = false): SavedTunnelIds {
        val ids = SavedTunnelIds(profile, id)
        saved[ids] = SavedTunnel(profile, profileName, id, name, device, port, PortKind.SSH)
        if (isOn) on += ids
        return ids
    }
    override fun find(ids: SavedTunnelIds) = saved[ids]
    override fun isStopping(tunnel: SavedTunnel) = SavedTunnelIds(tunnel.profileId, tunnel.connectionId) in stopping
    override fun isOn(tunnel: SavedTunnel) = SavedTunnelIds(tunnel.profileId, tunnel.connectionId) in on && !isStopping(tunnel)
    override fun pin(ids: SavedTunnelIds): Any? = saved[ids]?.let { listOf(it, ids in on, versions[ids] ?: 0) }
    override fun tabs(tunnel: SavedTunnel) = tabCount
}

/** [TunnelUp] as the tests need it: records what it was asked and answers [result]; [onCall] may change the world meanwhile. */
internal class FakeUp(var result: OpResult<Int> = OpResult.Done(40001), var onCall: (TunnelUpInput) -> Unit = {}, var delayMs: Long = 0) : Operation<TunnelUpInput, Int> {
    override val name = "tunnel_up"
    override val effect = Effect.CHANGE_LOCAL
    override val actionClass = ru.adsrv.frpjump.ops.ActionClass.APP_CHANGE
    val calls = ArrayList<TunnelUpInput>()
    override suspend fun invoke(input: TunnelUpInput): OpResult<Int> { calls += input; onCall(input); if (delayMs > 0) kotlinx.coroutines.delay(delayMs); return result }
    companion object {
        fun notListening() = OpResult.Failed(TunnelFailure.DeviceNotListening(DevicePortNotOpen(22)), RemoteOutcome.UNKNOWN)
        fun didNotComeUp() = OpResult.Failed(TunnelFailure.DidNotComeUp(null), RemoteOutcome.UNKNOWN)
    }
}

/** [TunnelDown] as the tests need it: records the targets. */
internal class FakeDown(var onCall: (TunnelDownTarget) -> Unit = {}) : Operation<TunnelDownTarget, Unit> {
    override val name = "tunnel_down"
    override val effect = Effect.CHANGE_LOCAL
    override val actionClass = ru.adsrv.frpjump.ops.ActionClass.APP_CHANGE
    val targets = ArrayList<TunnelDownTarget>()
    override suspend fun invoke(input: TunnelDownTarget): OpResult<Unit> { targets += input; onCall(input); return OpResult.Done(Unit) }
}

/** The assistant's tunnels over fakes. */
internal class TunnelFixture(val host: FakeSavedTunnels = FakeSavedTunnels(), val up: FakeUp = FakeUp(), val down: FakeDown = FakeDown()) {
    val tunnels = AssistantTunnels(host, up, down)
}
