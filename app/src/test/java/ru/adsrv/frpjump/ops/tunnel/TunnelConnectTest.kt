package ru.adsrv.frpjump.ops.tunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ops.Effect
import ru.adsrv.frpjump.ops.OpResult
import ru.adsrv.frpjump.ops.TunnelRefusal
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.TunnelRef

/** Connect, moved out of the Connections actions: revive, await a disconnect being done, connect for the kind. */
class TunnelConnectTest {
    private val input = TunnelConnectInput("home", "wb", 22, PortKind.MQTT)

    private class Host(
        val known: Boolean = true,
        var waiting: Boolean = false,
        var going: Boolean = false,
        val gate: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : TunnelConnectHost {
        val log = mutableListOf<String>()
        val connects = mutableListOf<Triple<String, Int, PortKind>>()
        override fun tunnels(profile: String) = if (known && profile == "home") object : TunnelConnectTunnels {
            override fun connectAsync(device: String, port: Int, kind: PortKind) { log += "connect"; connects += Triple(device, port, kind) }
        } else null
        override fun revive(tunnel: TunnelRef): Boolean { log += "revive"; return waiting }
        override fun disconnecting(tunnel: TunnelRef): Boolean { log += "disconnecting"; return going }
        override suspend fun awaitDisconnected(tunnel: TunnelRef) { log += "await-start"; gate.await(); log += "await-end" }
    }

    @Test fun `declares its name and that it changes only this device`() {
        val op = TunnelConnect(Host())
        assertEquals("tunnel_connect", op.name)
        assertEquals(Effect.CHANGE_LOCAL, op.effect)
        assertNull(op.target(input))
    }

    @Test fun `a tunnel that is down is connected once for the kind, before any suspension`() = runBlocking {
        val host = Host()
        var result: OpResult<Unit>? = null
        // Started undispatched, as the screens do: what it does at once is done when launch returns.
        val job = launch(start = CoroutineStart.UNDISPATCHED) { result = TunnelConnect(host)(input) }
        assertEquals(listOf(Triple("wb", 22, PortKind.MQTT)), host.connects)
        assertEquals(OpResult.Done(Unit), result)
        job.join()
        assertEquals(listOf("revive", "disconnecting", "connect"), host.log)
    }

    @Test fun `a tunnel whose disconnect waits for Undo is revived and not connected`() = runBlocking {
        val host = Host(waiting = true)
        assertEquals(OpResult.Done(Unit), TunnelConnect(host)(input))
        assertEquals(listOf("revive"), host.log)
        assertTrue(host.connects.isEmpty())
    }

    @Test fun `a disconnect being done is awaited first and only then the tunnel is connected`() = runBlocking {
        val host = Host(going = true)
        val job = launch(start = CoroutineStart.UNDISPATCHED) { TunnelConnect(host)(input) }
        assertEquals(listOf("revive", "disconnecting", "await-start"), host.log)
        assertTrue("nothing connects into a tunnel going down", host.connects.isEmpty())
        assertFalse(job.isCompleted)
        host.gate.complete(Unit)
        job.join()
        assertEquals(listOf("revive", "disconnecting", "await-start", "await-end", "connect"), host.log)
    }

    @Test fun `a forgotten profile is refused and nothing is connected`() = runBlocking {
        val host = Host(known = false)
        assertEquals(OpResult.Refused(TunnelRefusal.ProfileGone), TunnelConnect(host)(input))
        assertTrue(host.connects.isEmpty())
    }
}
