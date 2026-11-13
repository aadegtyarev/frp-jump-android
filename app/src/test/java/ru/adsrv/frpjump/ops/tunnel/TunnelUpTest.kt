package ru.adsrv.frpjump.ops.tunnel

import ru.adsrv.frpjump.errors.DevicePortNotOpen
import ru.adsrv.frpjump.errors.DomainError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.ops.*
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.localPortKey
import ru.adsrv.frpjump.tunnel.*

/** What "bring the tunnel up and wait" does, moved out of the Connections actions: every branch of the old `tunnelPort`. */
class TunnelUpTest {
    private val input = TunnelUpInput("home", "wb", 22, PortKind.SSH)
    private fun tunnel(localPort: Int = 4022) = Tunnel("wb", 22, "g", localPort, VisitorState(TunnelStatus.DIRECT))

    private class Tunnels(start: TunnelsState = TunnelsState(), val deviceAnswer: DevicePortNotOpen? = null) : TunnelUpTunnels {
        override val state = MutableStateFlow(start)
        val connects = mutableListOf<Triple<String, Int, PortKind>>()
        val devices = mutableListOf<Boolean>()
        override fun connectAsync(device: String, port: Int, kind: PortKind) { connects += Triple(device, port, kind) }
        override suspend fun awaitDevice(device: String, port: Int, fresh: Boolean): DevicePortNotOpen? { devices += fresh; return deviceAnswer }
    }

    private class Host(val tunnels: Tunnels?, val reviveAnswer: Boolean = true) : TunnelUpHost {
        val revived = mutableListOf<TunnelRef>()
        val awaited = mutableListOf<TunnelRef>()
        override fun tunnels(profile: String) = tunnels.takeIf { profile == "home" }
        override fun revive(tunnel: TunnelRef): Boolean { revived += tunnel; return reviveAnswer }
        override suspend fun awaitDisconnected(tunnel: TunnelRef) { awaited += tunnel }
    }

    @Test fun `declares its name and that it changes only this device`() {
        val up = TunnelUp(Host(null))
        assertEquals("tunnel_up", up.name)
        assertEquals(Effect.CHANGE_LOCAL, up.effect)
        assertNull(up.target(input))
    }

    @Test fun `a forgotten profile is refused and nothing is touched`() = runBlocking {
        val host = Host(Tunnels())
        assertEquals(OpResult.Refused(TunnelRefusal.ProfileGone), TunnelUp(host)(input.copy(profile = "gone")))
        assertEquals(emptyList<TunnelRef>(), host.revived + host.awaited)
        assertEquals(emptyList<Any>(), host.tunnels!!.connects)
    }

    @Test fun `a tunnel that is up is used as it is and its device is not forced`() = runBlocking {
        val tunnels = Tunnels(TunnelsState(tunnels = listOf(tunnel())))
        assertEquals(OpResult.Done(4022), TunnelUp(Host(tunnels))(input))
        assertEquals(emptyList<Any>(), tunnels.connects)
        assertEquals(listOf(false), tunnels.devices)
    }

    @Test fun `a tunnel that is down is connected once for the kind and waited for, and its device is waited for as fresh`() = runBlocking {
        val tunnels = Tunnels()
        val job = async { TunnelUp(Host(tunnels), waitMs = 5_000)(input.copy(kind = PortKind.MQTT)) }
        withTimeout(2_000) { while (tunnels.connects.isEmpty()) delay(5) }
        tunnels.state.value = TunnelsState(tunnels = listOf(tunnel(4100)))
        assertEquals(OpResult.Done(4100), withTimeout(2_000) { job.await() })
        assertEquals(listOf(Triple("wb", 22, PortKind.MQTT)), tunnels.connects)
        assertEquals(listOf(true), tunnels.devices)
    }

    @Test fun `a connect already in flight is not started again`() = runBlocking {
        val key = localPortKey("wb", 22)
        val tunnels = Tunnels(TunnelsState(pending = mapOf(key to PendingOp.CONNECTING)))
        val result = TunnelUp(Host(tunnels), waitMs = 30)(input)
        assertEquals(emptyList<Any>(), tunnels.connects)
        assertTrue(result is OpResult.Failed)
    }

    @Test fun `a tunnel whose disconnect waits for Undo is revived and not waited for, else its disconnect is awaited first`() = runBlocking {
        val ref = TunnelRef("home", "wb", 22)
        val revived = Host(Tunnels(TunnelsState(tunnels = listOf(tunnel()))), reviveAnswer = true)
        TunnelUp(revived)(input)
        assertEquals(listOf(ref), revived.revived); assertEquals(emptyList<TunnelRef>(), revived.awaited)
        val going = Host(Tunnels(TunnelsState(tunnels = listOf(tunnel()))), reviveAnswer = false)
        TunnelUp(going)(input)
        assertEquals(listOf(ref), going.revived); assertEquals(listOf(ref), going.awaited)
    }

    @Test fun `a tunnel that does not come up in time fails with the manager's last typed error, or none, and its remote outcome unknown`() = runBlocking {
        val key = localPortKey("wb", 22)
        val withError = Tunnels(TunnelsState(errors = mapOf(key to DomainError.ServerError("server said no"))))
        assertEquals(OpResult.Failed(TunnelFailure.DidNotComeUp(DomainError.ServerError("server said no")), RemoteOutcome.UNKNOWN), TunnelUp(Host(withError), waitMs = 30)(input))
        assertEquals(OpResult.Failed(TunnelFailure.DidNotComeUp(null), RemoteOutcome.UNKNOWN), TunnelUp(Host(Tunnels()), waitMs = 30)(input))
    }

    @Test fun `a device that does not open the port fails with the port that is not open and an unknown outcome`() = runBlocking {
        val tunnels = Tunnels(TunnelsState(tunnels = listOf(tunnel())), deviceAnswer = DevicePortNotOpen(80))
        assertEquals(OpResult.Failed(TunnelFailure.DeviceNotListening(DevicePortNotOpen(80)), RemoteOutcome.UNKNOWN), TunnelUp(Host(tunnels))(input))
    }

    @Test fun `cancellation while waiting propagates and is not a result`() = runBlocking {
        val job = launch(Dispatchers.Default) { TunnelUp(Host(Tunnels()), waitMs = 60_000)(input) }
        delay(50)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
    }

    @Test fun `the wait is 45 s and the Connections actions use that default`() {
        assertEquals(45_000L, TunnelUp.TUNNEL_WAIT_MS)
        val source = java.io.File("src/main/java/ru/adsrv/frpjump/ui/connections/ConnectionActions.kt").readText()
        assertTrue(source.contains("TunnelUp(object : TunnelUpHost"))
        assertFalse("ConnectionActions must not pick its own wait", source.contains("waitMs"))
        assertFalse(source.contains("TUNNEL_WAIT_MS"))
    }

    @Test fun `a disconnect being done is awaited before the state is read and before the connect`() = runBlocking {
        val log = java.util.concurrent.CopyOnWriteArrayList<String>()
        val inner = MutableStateFlow(TunnelsState())
        val tunnels = object : TunnelUpTunnels {
            override val state = object : kotlinx.coroutines.flow.StateFlow<TunnelsState> by inner {
                override val value: TunnelsState get() { log += "read"; return inner.value }
            }
            override fun connectAsync(device: String, port: Int, kind: PortKind) { log += "connect"; inner.value = TunnelsState(tunnels = listOf(tunnel())) }
            override suspend fun awaitDevice(device: String, port: Int, fresh: Boolean): DevicePortNotOpen? = null
        }
        val gate = CompletableDeferred<Unit>()
        val host = object : TunnelUpTunnels by tunnels, TunnelUpHost {
            override fun tunnels(profile: String) = tunnels
            override fun revive(tunnel: TunnelRef): Boolean { log += "revive"; return false }
            override suspend fun awaitDisconnected(tunnel: TunnelRef) { log += "await-start"; gate.await(); log += "await-end" }
        }
        val job = async { TunnelUp(host, waitMs = 2_000)(input) }
        withTimeout(2_000) { while ("await-start" !in log) delay(5) }
        delay(100)
        assertEquals("nothing reads or connects while the disconnect is being done", listOf("revive", "await-start"), log.toList())
        gate.complete(Unit)
        assertEquals(OpResult.Done(4022), withTimeout(2_000) { job.await() })
        assertTrue(log.indexOf("await-end") < log.indexOf("read"))
        assertTrue(log.indexOf("await-end") < log.indexOf("connect"))
    }

    @Test fun `the last error is read after the wait, not before it`() = runBlocking {
        val key = localPortKey("wb", 22)
        val tunnels = Tunnels()
        val job = async { TunnelUp(Host(tunnels), waitMs = 300)(input) }
        delay(100)
        tunnels.state.value = TunnelsState(errors = mapOf(key to DomainError.ServerError("late error")))
        assertEquals(OpResult.Failed(TunnelFailure.DidNotComeUp(DomainError.ServerError("late error")), RemoteOutcome.UNKNOWN), job.await())
    }
}
