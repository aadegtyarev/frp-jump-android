package ru.adsrv.frpjump.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.credentials
import ru.adsrv.frpjump.session.ProfileSession
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.CredentialStore
import java.io.IOException

class ServerFeaturesTest {
    private val url = "https://way.example"

    @Test
    fun `cache round-trips the features of the same server`() {
        val cache = ServerFeaturesCache(MemoryStorage())
        cache.save(url, listOf("device-display"))
        assertEquals(listOf("device-display"), cache.load(url))
    }

    @Test
    fun `another server's, unreadable or wiped features are absent`() {
        val cache = ServerFeaturesCache(MemoryStorage())
        cache.save(url, listOf("device-display"))
        assertNull(cache.load("https://other.example"))
        cache.wipe()
        assertNull(cache.load(url))
        assertNull(ServerFeaturesCache(MemoryStorage("{not json")).load(url))
    }

    /** The server's `/version` and `/devices` answers; null throws as a network failure. */
    private class FakeServer {
        var version: String? = """{"protocol_version":1,"package_version":"0.4.0","features":["device-display"]}"""
        var devices: String? = """[{"name":"wb01","enrolled_at":"2026-10-01T00:00:00","last_seen_at":null,"enabled":true,"label":"Щиток","kind":"wirenboard"}]"""

        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                val body = when (chain.request().url.pathSegments.last()) {
                    "version" -> version
                    "devices" -> devices
                    "heartbeat" -> ""
                    "desired-state" -> """{"device_id":"phone","server_addr":"relay","server_port":7000,"consumed":[{"grant_id":"g1","secret":"synthetic","service_name":"svc","protocol":"tcp","exposer_device_name":"wb01","target_port":80}]}"""
                    else -> null
                } ?: throw IOException("unreachable")
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            },
        ).build()
    }

    private val server = FakeServer()
    private val storage = MemoryStorage()

    private fun CoroutineScope.directory(): DeviceDirectory {
        val keystore = FakeKeystore()
        val store = CredentialStore(MemoryStorage(), keystore.box("p")).apply { save(credentials("way.example")) }
        return DeviceDirectory(store, FrpJumpApi(server.http), ProfileSession(), DeviceListCache(MemoryStorage()), ServerFeaturesCache(storage), this)
    }

    @Test
    fun `no stored answer yet means not supported`() = runBlocking {
        server.version = null
        val d = directory()
        d.restore()
        assertFalse(d.checkFeatures())
        d.refresh()
        yield()
        assertFalse(d.state.value.display)
        assertNull(d.state.value.devices!!.single().label)
    }

    @Test
    fun `an answer turns labels on, is stored, and survives a failed check after a restart`() = runBlocking {
        val first = directory()
        first.refresh()
        // The check the refresh started runs on its own.
        withTimeout(5_000) { first.state.first { it.display } }
        // Waits for the lock the check stores the answer under.
        first.refresh(wait = true)
        assertEquals("Щиток", first.state.value.devices!!.single().label)
        assertEquals(listOf("device-display"), ServerFeaturesCache(storage).load(url))

        server.version = null
        val again = directory()
        again.restore()
        assertFalse(again.checkFeatures())
        assertTrue(again.state.value.display)
    }

    @Test
    fun `incompatible version cannot enable disable or replace stored features`() = runBlocking {
        for (prior in listOf<List<String>?>(null, emptyList(), listOf("device-display", "future-feature"))) {
            for (version in listOf(Int.MIN_VALUE, -1, 0, 2, Int.MAX_VALUE)) {
                for (features in listOf("", ",\"features\":[]", ",\"features\":[\"device-display\"]")) {
                    ServerFeaturesCache(storage).wipe()
                    if (prior != null) ServerFeaturesCache(storage).save(url, prior)
                    val d = directory()
                    d.restore()
                    // Fetch rows without starting a background features check.
                    server.version = """{"protocol_version":$version,"package_version":"synthetic"$features}"""
                    d.pull()
                    val before = d.state.value
                    val bytes = storage.read()
                    assertFalse("protocol=$version prior=$prior features=$features", d.checkFeatures())
                    assertEquals(before, d.state.value)
                    assertEquals(bytes, storage.read())
                    assertEquals(prior, ServerFeaturesCache(storage).load(url))
                    assertEquals(prior?.contains("device-display") == true, d.state.value.display)
                    assertEquals(if (d.state.value.display) "Щиток" else null, d.state.value.devices!!.single().label)
                    // A later compatible answer can still update the features.
                    server.version = """{"protocol_version":1,"package_version":"synthetic","features":["device-display"]}"""
                    assertTrue(d.checkFeatures())
                    assertTrue(d.state.value.display)
                }
            }
        }
    }

    @Test
    fun `rejected feature check leaves the running tunnel manager and frpc untouched`() = runBlocking {
        val session = ProfileSession()
        val runner = object : ru.adsrv.frpjump.tunnel.FrpcControl {
            override val states = kotlinx.coroutines.flow.MutableStateFlow<Map<String, ru.adsrv.frpjump.tunnel.VisitorState>>(emptyMap())
            override var heldPorts: Set<Int> = emptySet()
            var applies = 0
            var stops = 0
            override fun apply(launch: ru.adsrv.frpjump.tunnel.FrpcLaunch, ports: Set<Int>) { applies++; heldPorts = ports }
            override fun stop() { stops++ }
            override fun wipe() { stop() }
            override fun expireTrying() = Unit
            override fun refusalsOf(grant: String) = 0
            override fun deviceReady(grant: String, refusalsBefore: Int) = Unit
            override fun recentLog() = emptyList<String>()
        }
        val c = credentials("way.example")
        val api = FrpJumpApi(server.http)
        val manager = ru.adsrv.frpjump.tunnel.TunnelManager(
            "p", { ru.adsrv.frpjump.store.StoredEnrollment.Enrolled(c) }, api, session,
            ru.adsrv.frpjump.ports.LocalPortStore(MemoryStorage()), runner, { true },
            touchPort = { ru.adsrv.frpjump.tunnel.Touch.OPEN },
        )
        val store = CredentialStore(MemoryStorage(), FakeKeystore().box("p")).apply { save(c) }
        val d = DeviceDirectory(store, api, session, DeviceListCache(MemoryStorage()), ServerFeaturesCache(storage), this)
        try {
            manager.sync()
            assertEquals(1, runner.applies)
            val before = withTimeout(5_000) { manager.state.first { it.tunnels.size == 1 } }
            assertEquals(1, before.tunnels.size)
            val ports = runner.heldPorts
            server.version = """{"protocol_version":2,"package_version":"synthetic","features":["device-display"]}"""
            assertFalse(d.checkFeatures())
            assertEquals(before, manager.state.value)
            assertEquals(ports, runner.heldPorts)
            assertEquals(1, runner.applies)
            assertEquals(0, runner.stops)
            assertFalse(session.removed.value)
        } finally {
            manager.close()
        }
    }

    @Test
    fun `an old server without features turns them off`() = runBlocking {
        val d = directory()
        d.refresh()
        withTimeout(5_000) { d.state.first { it.display } }
        server.version = """{"protocol_version":1,"package_version":"0.3.0"}"""
        assertTrue(d.checkFeatures())
        assertFalse(d.state.value.display)
        assertNull(d.state.value.devices!!.single().label)
        assertEquals(emptyList<String>(), ServerFeaturesCache(storage).load(url))
    }
}
