package ru.adsrv.frpjump.tunnel

import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.api.AgentApi
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.NetworkException
import ru.adsrv.frpjump.api.ConnectResponse
import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.api.DesiredStateResponse
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.restore.RestoreRecord
import ru.adsrv.frpjump.session.ProfileSession
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.Credentials
import ru.adsrv.frpjump.store.StoredEnrollment

class TunnelManagerTest {
    private val grant = ConsumedGrant("g1", "secret", "svc", "tcp", "wb", 80)

    private class FakeFrpc : FrpcControl {
        override val states = MutableStateFlow<Map<String, VisitorState>>(emptyMap())
        override var heldPorts: Set<Int> = emptySet()
        @Volatile var running = false
        @Volatile var applies = 0
        @Volatile var wiped = false
        @Volatile var last: FrpcLaunch? = null
        @Volatile var expiries = 0

        override fun apply(launch: FrpcLaunch, ports: Set<Int>) {
            running = true
            applies++
            heldPorts = ports
            last = launch
        }

        override fun expireTrying() {
            expiries++
        }

        override fun stop() {
            running = false
            heldPorts = emptySet()
        }

        override fun wipe() {
            stop()
            wiped = true
        }

        override fun recentLog() = emptyList<String>()
    }

    private inner class FakeApi : AgentApi {
        /** When set, heartbeat waits for it: a poll caught in flight. */
        @Volatile var gate: CompletableDeferred<Unit>? = null
        @Volatile var failure: Exception? = null
        @Volatile var consumed = listOf(grant)
        @Volatile var connectGate: CompletableDeferred<Unit>? = null

        override suspend fun heartbeat(c: Credentials) {
            gate?.await()
            failure?.let { throw it }
        }

        @Volatile var desiredFailure: Exception? = null

        override suspend fun desiredState(c: Credentials): DesiredStateResponse {
            desiredFailure?.let { throw it }
            return DesiredStateResponse("id", "relay", 7000, consumed)
        }

        override suspend fun connect(c: Credentials, deviceName: String, targetPort: Int, protocol: String): ConnectResponse {
            connectGate?.await()
            failure?.let { throw it }
            return ConnectResponse("g1", deviceName, targetPort, protocol)
        }

        /** The server drops the grant, as it does. */
        override suspend fun disconnect(c: Credentials, deviceName: String, targetPort: Int): Boolean {
            failure?.let { throw it }
            disconnected += "$deviceName:$targetPort"
            consumed = consumed.filterNot { it.exposerDeviceName == deviceName && it.targetPort == targetPort }
            return true
        }

        val disconnected = mutableListOf<String>()
    }

    private fun credentials(token: String) = Credentials("https://s", "id", "phone", token, "cert", "key", "ca", "relay", 7000)

    @Volatile private var stored: StoredEnrollment = StoredEnrollment.Enrolled(credentials("t1"))
    private val session = ProfileSession()
    private val frpc = FakeFrpc()
    private val api = FakeApi()
    private val ports = MemoryStorage()
    private var serviceStarts = 0
    @Volatile private var mode = DirectMode.ON
    private val record = RestoreRecord(MemoryStorage()).apply { load() }
    private val manager = TunnelManager(
        profile = "p1",
        enrollment = { stored },
        api = api,
        session = session,
        localPorts = LocalPortStore(ports),
        runner = frpc,
        startService = { serviceStarts++; true },
        directMode = { mode },
        recordTunnels = { record.set("p1", it) },
        pins = { pins },
        pinnedRetryMs = 200,
    ).apply { portRange = 47100..47199 }

    /** The profile's pinned local ports, as its connections give them. */
    @Volatile private var pins: Map<String, Int> = emptyMap()

    private suspend fun eventually(what: String, check: () -> Boolean) = withTimeout(2_000) {
        while (!check()) delay(10)
    }.also { assertTrue(what, check()) }

    @Test
    fun `a sync starts frpc and the service for a consumed grant`() = runBlocking {
        manager.sync()
        assertTrue(frpc.running)
        assertEquals(1, serviceStarts)
        eventually("the tunnel is listed") { manager.state.value.tunnels.map { it.grantId } == listOf("g1") }
    }

    @Test
    fun `a 401 marks the phone removed and stops frpc`() = runBlocking {
        manager.sync()
        api.failure = ApiException(401, "unknown token")
        manager.sync()
        assertTrue(session.removed.value)
        assertFalse(frpc.running)
        eventually("no tunnels listed") { manager.state.value.tunnels.isEmpty() }
    }

    @Test
    fun `removal seen elsewhere (the device list) stops frpc too`() = runBlocking {
        manager.sync()
        session.markRemoved()
        eventually("frpc stopped") { !frpc.running }
    }

    @Test
    fun `no sync starts frpc while the phone is removed`() = runBlocking {
        session.markRemoved()
        manager.sync()
        assertFalse(frpc.running)
        assertEquals(0, frpc.applies)
    }

    @Test
    fun `a late 401 for a token that is no longer stored does not mark removal`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        api.connectGate = gate
        api.failure = ApiException(401, "unknown token")
        val connect = async { manager.connect("wb", 80, PortKind.WEB) }
        delay(50)
        // Forgotten and enrolled again while the call was in flight.
        stored = StoredEnrollment.Enrolled(credentials("t2"))
        gate.complete(Unit)
        connect.await()
        assertFalse(session.removed.value)
    }

    @Test
    fun `a poll caught in flight by Forget cannot leave frpc running`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        api.gate = gate
        val poll = async { manager.sync() }
        // The poll holds the lock, waiting for the server.
        delay(50)
        val forget = async { manager.shutdown { stored = StoredEnrollment.NotEnrolled } }
        delay(50)
        gate.complete(Unit)
        poll.await()
        forget.await()
        assertFalse(frpc.running)
        assertTrue(frpc.wiped)
        assertEquals(StoredEnrollment.NotEnrolled, stored)
        assertEquals(emptyMap<String, Int>(), LocalPortStore(ports).load())
        // Later polls find no credentials and start nothing.
        val applies = frpc.applies
        api.gate = null
        manager.sync()
        assertEquals(applies, frpc.applies)
        assertFalse(frpc.running)
    }

    @Test
    fun `a sync without credentials stops a frpc that still runs`() = runBlocking {
        manager.sync()
        assertTrue(frpc.running)
        stored = StoredEnrollment.NotEnrolled
        manager.sync()
        assertFalse(frpc.running)
    }

    @Test
    fun `unreadable storage leaves frpc running`() = runBlocking {
        manager.sync()
        stored = StoredEnrollment.Unavailable("KeyStoreException")
        manager.sync()
        assertTrue(frpc.running)
    }

    @Test
    fun `direct on, kept or off decides the tunnel's visitors`() = runBlocking {
        manager.sync()
        assertEquals(true, frpc.last!!.visitors.single().direct)
        eventually("listed as direct") { manager.state.value.tunnels.single().direct == DirectMode.ON }
        mode = DirectMode.KEPT
        manager.sync()
        assertEquals(false, frpc.last!!.visitors.single().direct)
        eventually("listed as kept") { manager.state.value.tunnels.single().direct == DirectMode.KEPT }
        mode = DirectMode.OFF
        manager.sync()
        assertEquals(false, frpc.last!!.visitors.single().direct)
    }

    @Test
    fun `reapply changes the visitors without asking the server`() = runBlocking {
        manager.sync()
        api.failure = IllegalStateException("offline")
        mode = DirectMode.KEPT
        manager.reapply()
        assertEquals(false, frpc.last!!.visitors.single().direct)
    }

    @Test
    fun `retry direct gives the device's tunnels not direct a new attempt, kept across polls`() = runBlocking {
        api.consumed = listOf(grant, ConsumedGrant("g2", "secret", "svc", "ssh", "wb", 22), ConsumedGrant("g3", "secret", "svc", "tcp", "other", 80))
        manager.sync()
        frpc.states.value = mapOf(
            "g1" to VisitorState(TunnelStatus.RELAYED, directFailed = true),
            "g2" to VisitorState(TunnelStatus.DIRECT),
            "g3" to VisitorState(TunnelStatus.RELAYED, directFailed = true),
        )
        assertTrue(manager.retryDirect("wb"))
        assertEquals(mapOf("g1" to 1, "g2" to 0, "g3" to 0), frpc.last!!.visitors.associate { it.grantId to it.attempt })
        manager.sync()
        assertEquals("a poll does not undo it", 1, frpc.last!!.visitors.first().attempt)
        assertFalse("no such device", manager.retryDirect("nowhere"))
    }

    @Test
    fun `turning direct again starts a new attempt`() = runBlocking {
        manager.sync()
        assertEquals(0, frpc.last!!.visitors.single().attempt)
        mode = DirectMode.KEPT
        manager.reapply()
        mode = DirectMode.ON
        manager.reapply()
        assertEquals(true, frpc.last!!.visitors.single().direct)
        assertEquals("not the old xtcp name: its late lines would count", 1, frpc.last!!.visitors.single().attempt)
        manager.reapply()
        assertEquals("only on the change", 1, frpc.last!!.visitors.single().attempt)
    }

    @Test
    fun `nothing is reapplied after the tunnels stopped`() = runBlocking {
        manager.sync()
        stored = StoredEnrollment.NotEnrolled
        manager.sync()
        stored = StoredEnrollment.Enrolled(credentials("t1"))
        val applies = frpc.applies
        manager.reapply()
        assertEquals(applies, frpc.applies)
        assertFalse(frpc.running)
    }

    @Test
    fun `trying direct is expired while it lasts`() = runBlocking {
        manager.sync()
        frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.TRYING))
        withTimeout(3_000) { while (frpc.expiries == 0) delay(20) }
        frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.DIRECT))
        val expiries = frpc.expiries
        delay(1_500)
        assertTrue("no ticks once it is decided", frpc.expiries <= expiries + 1)
    }

    @Test
    fun `a device not ready yet gets another attempt shortly`() = runBlocking {
        manager.sync()
        frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.TRYING, waitingForPeer = true))
        withTimeout(5_000) { while (frpc.last!!.visitors.single().attempt == 0) delay(20) }
        assertEquals(1, frpc.last!!.visitors.single().attempt)
    }

    @Test
    fun `a tunnel whose local port another app took moves to a free one`() = runBlocking {
        manager.sync()
        val first = frpc.last!!.visitors.single().localPort
        ServerSocket().use { squatter ->
            squatter.reuseAddress = true
            squatter.bind(InetSocketAddress("127.0.0.1", first))
            frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.FAILED, "listen tcp 127.0.0.1:$first: bind: address already in use"))
            withTimeout(5_000) { while (frpc.last!!.visitors.single().localPort == first) delay(20) }
        }
        assertTrue(frpc.last!!.visitors.single().localPort in 47100..47199)
    }

    @Test
    fun `a port taken again and again is moved three times, then left failed`() = runBlocking {
        manager.sync()
        repeat(5) {
            val before = frpc.applies
            frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.FAILED, "bind: address already in use #$it"))
            if (it < 3) withTimeout(5_000) { while (frpc.applies == before) delay(20) } else delay(300)
        }
        assertEquals(4, frpc.applies)
    }

    @Test
    fun `a tunnel that worked again may move again`() = runBlocking {
        manager.sync()
        repeat(3) {
            val before = frpc.applies
            frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.FAILED, "bind: address already in use #$it"))
            withTimeout(5_000) { while (frpc.applies == before) delay(20) }
        }
        frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.RELAYED))
        delay(100)
        val before = frpc.applies
        frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.FAILED, "bind: address already in use again"))
        withTimeout(5_000) { while (frpc.applies == before) delay(20) }
    }

    @Test
    fun `a pinned tunnel is served on its pin and never moves when its port is taken`() = runBlocking {
        pins = mapOf("wb:80" to 18080)
        manager.sync()
        assertEquals(18080, frpc.last!!.visitors.single().localPort)
        frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.FAILED, "listen tcp 127.0.0.1:18080: bind: address already in use"))
        delay(100)
        // Not moved (a rebind would apply at once, on another port).
        assertEquals(1, frpc.applies)
        val tunnel = manager.state.value.tunnels.single()
        assertTrue(tunnel.pinned)
        assertEquals(18080, tunnel.localPort)
        // Said plainly, not frpc's words.
        assertFalse(tunnel.state.detail!!.contains("address already in use"))
    }

    @Test
    fun `a pinned tunnel whose port is taken tries the same port again until it is up`() = runBlocking {
        pins = mapOf("wb:80" to 18080)
        manager.sync()
        frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.FAILED, "bind: address already in use"))
        // Alone in frpc: it restarts on the same port after the wait.
        withTimeout(5_000) { while (frpc.applies < 2) delay(20) }
        assertEquals(18080, frpc.last!!.visitors.single().localPort)
        frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.RELAYED))
        val applies = frpc.applies
        delay(500)
        assertEquals(applies, frpc.applies)
    }

    @Test
    fun `retrying a pinned port leaves the other tunnels in place and takes it back`() = runBlocking {
        pins = mapOf("wb:80" to 18080)
        api.consumed = listOf(grant, ConsumedGrant("g2", "secret2", "svc2", "tcp", "wb", 22))
        manager.sync()
        frpc.states.value = mapOf(
            "g1" to VisitorState(TunnelStatus.FAILED, "bind: address already in use"),
            "g2" to VisitorState(TunnelStatus.RELAYED),
        )
        withTimeout(5_000) { while (frpc.applies < 3) delay(20) }
        // Dropped for one reload, then back on its pin; the other tunnel never left.
        assertEquals(setOf(18080), frpc.last!!.visitors.filter { it.grantId == "g1" }.map { it.localPort }.toSet())
        assertTrue(frpc.last!!.visitors.any { it.grantId == "g2" })
    }

    @Test
    fun `other start errors do not move the tunnel`() = runBlocking {
        manager.sync()
        frpc.states.value = mapOf("g1" to VisitorState(TunnelStatus.FAILED, "proxy not found"))
        delay(300)
        assertEquals(1, frpc.applies)
    }

    // The restore record: which profiles a cold start brings back.

    private fun recorded() = record.profiles.value?.get("p1")

    @Test
    fun `connecting records the profile's tunnels, a later poll their new count`() = runBlocking {
        manager.connect("wb", 80, PortKind.WEB)
        assertEquals(1, recorded())
        api.consumed = listOf(grant, ConsumedGrant("g2", "secret", "svc", "ssh", "wb", 22))
        manager.sync()
        assertEquals(2, recorded())
    }

    @Test
    fun `disconnecting the last tunnel clears the record, one of two does not`() = runBlocking {
        api.consumed = listOf(grant, ConsumedGrant("g2", "secret", "svc", "ssh", "wb", 22))
        manager.sync()
        manager.disconnect("wb", 22)
        assertEquals(1, recorded())
        manager.disconnect("wb", 80)
        assertEquals(null, recorded())
        assertFalse(frpc.running)
    }

    @Test
    fun `a tunnel disconnected elsewhere clears the record on the next poll`() = runBlocking {
        manager.sync()
        api.consumed = emptyList()
        manager.sync()
        assertEquals(null, recorded())
    }

    @Test
    fun `disconnect all clears the record once the server was told`() = runBlocking {
        manager.sync()
        assertTrue(manager.disconnectAll())
        assertEquals(null, recorded())
        assertFalse(frpc.running)
    }

    @Test
    fun `disconnect all the server could not hear stops frpc but keeps the record`() = runBlocking {
        manager.sync()
        api.failure = IOException("offline")
        assertFalse(manager.disconnectAll())
        assertFalse(frpc.running)
        assertEquals("they may come back", 1, recorded())
    }

    @Test
    fun `disconnect all while tunnels are still being restored disconnects them on the server`() = runBlocking {
        // A cold start: recorded, nothing applied yet.
        record.set("p1", 1)
        assertTrue(manager.disconnectAll())
        assertEquals(listOf("wb:80"), api.disconnected)
        assertEquals(null, recorded())
        assertFalse("the next poll brings nothing back", frpc.running)
    }

    @Test
    fun `disconnect all while restoring keeps the record when the server's list cannot be read`() = runBlocking {
        record.set("p1", 1)
        api.desiredFailure = IOException("offline")
        assertFalse(manager.disconnectAll())
        assertEquals(1, recorded())
    }

    @Test
    fun `disconnect all with the Keystore locked keeps the record`() = runBlocking {
        record.set("p1", 1)
        stored = StoredEnrollment.Unavailable("KeyStoreException")
        assertFalse(manager.disconnectAll())
        assertEquals(1, recorded())
    }

    @Test
    fun `polls say what a restore waits for`() = runBlocking {
        api.failure = NetworkException(NetworkException.Reason.UNREACHABLE, "s", "offline", IOException("offline"))
        manager.poll()
        eventually("waiting for network") { manager.state.value.waiting == Waiting.NETWORK }
        api.failure = null
        stored = StoredEnrollment.Unavailable("KeyStoreException")
        manager.poll()
        eventually("waiting for unlock") { manager.state.value.waiting == Waiting.UNLOCK }
        stored = StoredEnrollment.Enrolled(credentials("t1"))
        manager.poll()
        eventually("nothing to wait for") { manager.state.value.waiting == null }
        api.failure = ApiException(500, "boom")
        manager.poll()
        assertEquals("a server error is not the network", null, manager.state.value.waiting)
    }

    @Test
    fun `forget clears the record`() = runBlocking {
        manager.sync()
        manager.shutdown { stored = StoredEnrollment.NotEnrolled }
        assertEquals(null, recorded())
    }

    @Test
    fun `removal by the server clears the record`() = runBlocking {
        manager.sync()
        api.failure = ApiException(401, "unknown token")
        manager.sync()
        assertEquals(null, recorded())
    }

    @Test
    fun `a failed poll or a busy Keystore keeps the record`() = runBlocking {
        // As after a cold start: the record says 1, nothing runs, the server is not reachable yet.
        record.set("p1", 1)
        api.failure = IllegalStateException("offline")
        assertFalse(manager.poll())
        assertEquals(1, recorded())
        api.failure = null
        stored = StoredEnrollment.Unavailable("KeyStoreException")
        assertFalse(manager.poll())
        assertEquals(1, recorded())
        stored = StoredEnrollment.Enrolled(credentials("t1"))
        assertTrue(manager.poll())
        assertTrue("brought back", frpc.running)
        assertEquals(1, recorded())
    }
}
