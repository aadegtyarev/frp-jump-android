package ru.adsrv.frpjump.devices

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.adsrv.frpjump.api.ApiException
import ru.adsrv.frpjump.api.FrpJumpApi
import ru.adsrv.frpjump.api.displayBody
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.credentials
import ru.adsrv.frpjump.session.ProfileSession
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.CredentialStore

class DeviceDisplaySaveTest {
    @Test
    fun `the body carries only the changed fields, a clear as an explicit null`() {
        assertEquals("""{"label":"Щиток"}""", displayBody(DisplayChange(DisplayValue("Щиток"), null)))
        assertEquals("""{"icon":null}""", displayBody(DisplayChange(null, DisplayValue(null))))
        assertEquals("""{"label":null,"icon":"pc"}""", displayBody(DisplayChange(DisplayValue(null), DisplayValue("pc"))))
    }

    /** A server whose `/display` answers [display] (status, body); requests are kept in [seen]. */
    private class FakeServer {
        var display: Pair<Int, String> = 200 to
            """{"name":"wb01","enrolled_at":"2026-10-01T00:00:00","last_seen_at":null,"enabled":true,"label":"Гараж","icon":"camera","kind":"wirenboard"}"""
        val seen = mutableListOf<Pair<Request, String>>()

        val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(
            Interceptor { chain ->
                val request = chain.request()
                val sent = request.body?.let { Buffer().apply { it.writeTo(this) }.readUtf8() }.orEmpty()
                seen += request to sent
                val (code, body) = when (request.url.pathSegments.last()) {
                    "version" -> 200 to """{"protocol_version":1,"package_version":"0.3.19","features":["device-display"]}"""
                    "devices" -> 200 to
                        """[{"name":"wb01","enrolled_at":"2026-10-01T00:00:00","last_seen_at":null,"enabled":true,"label":"Щиток","kind":"wirenboard"}]"""
                    "display" -> display
                    else -> 404 to """{"detail":"Not Found"}"""
                }
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("x")
                    .body(body.toResponseBody("application/json".toMediaType())).build()
            },
        ).build()
    }

    private val server = FakeServer()
    private val listStorage = MemoryStorage()

    private fun CoroutineScope.directory(): DeviceDirectory {
        val store = CredentialStore(MemoryStorage(), FakeKeystore().box("p")).apply { save(credentials("way.example")) }
        return DeviceDirectory(store, FrpJumpApi(server.http), ProfileSession(), DeviceListCache(listStorage), ServerFeaturesCache(MemoryStorage()), this)
    }

    @Test
    fun `a save posts to the device's display route and the list shows the answer at once`() = runBlocking {
        val d = directory()
        d.refresh()
        withTimeout(5_000) { d.state.first { it.display } }
        d.refresh(wait = true)

        d.setDisplay("wb01", DisplayChange(DisplayValue("Гараж"), null))

        val (request, body) = server.seen.last()
        assertEquals("POST", request.method)
        assertEquals("/api/agent/devices/wb01/display", request.url.encodedPath)
        assertEquals("Bearer t-phone", request.header("Authorization"))
        assertEquals("""{"label":"Гараж"}""", body)
        // Taken from the answer, the icon included, not guessed from what was sent.
        val row = d.state.value.devices!!.single()
        assertEquals("Гараж", row.label)
        assertEquals(DeviceIcon.CAMERA, row.icon)
        assertEquals("camera", row.iconChoice)
        assertEquals("Гараж", DeviceListCache(listStorage).load("https://way.example", "phone")!!.devices.single().label)
    }

    @Test
    fun `a refused save leaves the list as it was and throws the server's reason`() = runBlocking {
        val d = directory()
        d.refresh()
        withTimeout(5_000) { d.state.first { it.display } }
        d.refresh(wait = true)
        server.display = 400 to """{"detail":"label has no visible characters"}"""
        try {
            d.setDisplay("wb01", DisplayChange(DisplayValue("‍"), null))
            fail("no error")
        } catch (e: ApiException) {
            assertEquals(400, e.status)
            assertEquals(DisplayFailure.Label(LabelRefusal.NOT_VISIBLE), displayFailure(e, DisplayChange(DisplayValue("x"), null)) { "" })
        }
        assertEquals("Щиток", d.state.value.devices!!.single().label)
        assertNull(d.state.value.devices!!.single().iconChoice)
        assertTrue(d.state.value.display)
    }
}
