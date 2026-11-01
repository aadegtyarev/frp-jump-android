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
