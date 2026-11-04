package ru.adsrv.frpjump.api

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Exercises decoding and acceptance through the real asynchronous OkHttp adapter, without sockets. */
@RunWith(Parameterized::class)
class VersionResponseTest(private val version: Int) {
    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "protocol={0}")
        fun versions() = listOf(Int.MIN_VALUE, -1, 0, 1, 2, Int.MAX_VALUE).map { arrayOf(it) }
    }

    private val requests = mutableListOf<Request>()
    private var body = """{"protocol_version":$version,"package_version":"synthetic","features":["device-display"]}"""
    private val api = FrpJumpApi(OkHttpClient.Builder().addInterceptor { chain ->
        synchronized(requests) { requests += chain.request() }
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            .code(200).message("OK").body(body.toResponseBody()).build()
    }.build())

    @Test
    fun `version accepts only the supported explicit value before returning features`() {
        if (version == PROTOCOL_VERSION) {
            val response = runBlocking { api.version("https://synthetic.example") }
            assertEquals(listOf("device-display"), response.features)
            assertEquals(version, response.protocolVersion)
        } else {
            val error = assertThrows(ProtocolMismatchException::class.java) {
                runBlocking { api.version("https://synthetic.example") }
            }
            assertEquals(version, error.serverVersion)
        }
        assertEquals(listOf("GET"), requests.map { it.method })
        assertEquals(null, requests.single().header("Authorization"))
    }

    @Test
    fun `omitting optional features does not bypass explicit version acceptance`() {
        body = """{"protocol_version":$version,"package_version":"synthetic"}"""
        if (version == PROTOCOL_VERSION) {
            assertEquals(emptyList<String>(), runBlocking { api.version("https://synthetic.example") }.features)
        } else {
            assertEquals(version, assertThrows(ProtocolMismatchException::class.java) {
                runBlocking { api.version("https://synthetic.example") }
            }.serverVersion)
        }
    }

    @Test
    fun `enrollment rejects incompatible preflight without spending its token`() {
        if (version == PROTOCOL_VERSION) return
        assertEquals(version, assertThrows(ProtocolMismatchException::class.java) {
            runBlocking { api.enroll("https://synthetic.example", "synthetic-single-use", "phone") }
        }.serverVersion)
        assertEquals(listOf("/api/agent/version"), requests.map { it.url.encodedPath })
        assertEquals("GET", requests.single().method)
        assertEquals(null, requests.single().body)
    }
}
