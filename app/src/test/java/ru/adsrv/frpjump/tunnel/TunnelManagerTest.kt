package ru.adsrv.frpjump.tunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.api.AgentApi
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.ConnectResponse
import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.api.DesiredStateResponse
import ru.adsrv.frpjump.ports.DevicePort
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.session.AppSession
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

        override fun apply(launch: FrpcLaunch, ports: Set<Int>) {
            running = true
            applies++
            heldPorts = ports
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

        override suspend fun desiredState(c: Credentials) = DesiredStateResponse("id", "relay", 7000, consumed)

        override suspend fun connect(c: Credentials, deviceName: String, targetPort: Int, protocol: String): ConnectResponse {
            connectGate?.await()
            failure?.let { throw it }
            return ConnectResponse("g1", deviceName, targetPort, protocol)
        }

        override suspend fun disconnect(c: Credentials, deviceName: String, targetPort: Int) = true
    }

    private fun credentials(token: String) = Credentials("https://s", "id", "phone", token, "cert", "key", "ca", "relay", 7000)

    @Volatile private var stored: StoredEnrollment = StoredEnrollment.Enrolled(credentials("t1"))
    private val session = AppSession()
    private val frpc = FakeFrpc()
    private val api = FakeApi()
    private val ports = MemoryStorage()
    private var serviceStarts = 0
    private val manager = TunnelManager(
        enrollment = { stored },
        api = api,
        session = session,
        localPorts = LocalPortStore(ports),
        runner = frpc,
        startService = { serviceStarts++; true },
        stopServiceNow = {},
    ).apply { portRange = 47100..47199 }

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
        val connect = async { manager.connect("wb", DevicePort(80, "Web", PortKind.WEB)) }
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
        assertEquals(null, ports.text)
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
}
