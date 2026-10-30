package ru.adsrv.frpjump.api

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Samples written from the server's pydantic models in `server/api.py`. */
class WireModelsTest {
    @Test
    fun enrollResponse() {
        val body = """
            {"device_id":"dev-0000","device_name":"phone","api_token":"placeholder-api-token",
             "cert_pem":"-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----\n",
             "key_pem":"-----BEGIN PRIVATE KEY-----\nBBBB\n-----END PRIVATE KEY-----\n",
             "ca_cert_pem":"-----BEGIN CERTIFICATE-----\nCCCC\n-----END CERTIFICATE-----\n",
             "server_addr":"relay.example.com","server_port":7000,"protocol_version":1}
        """.trimIndent()
        val r = WireJson.decodeFromString<EnrollResponse>(body)
        assertEquals("dev-0000", r.deviceId)
        assertEquals("phone", r.deviceName)
        assertEquals("placeholder-api-token", r.apiToken)
        assertEquals("-----BEGIN PRIVATE KEY-----\nBBBB\n-----END PRIVATE KEY-----\n", r.keyPem)
        assertEquals("relay.example.com", r.serverAddr)
        assertEquals(7000, r.serverPort)
        assertEquals(PROTOCOL_VERSION, r.protocolVersion)
    }

    @Test
    fun versionResponse() {
        val r = WireJson.decodeFromString<VersionResponse>("""{"protocol_version":1,"package_version":"0.9.0"}""")
        assertEquals(VersionResponse(PROTOCOL_VERSION, "0.9.0"), r)
    }

    @Test
    fun deviceList() {
        val body = """
            [{"name":"laptop","enrolled_at":"2026-10-01T08:00:00.000001","last_seen_at":"2026-10-30T11:59:58.123456","enabled":true},
             {"name":"wb7","enrolled_at":"2026-10-02T08:00:00","last_seen_at":null,"enabled":false}]
        """.trimIndent()
        val list = WireJson.decodeFromString<List<OwnedDevice>>(body)
        assertEquals(listOf("laptop", "wb7"), list.map { it.name })
        assertEquals("2026-10-30T11:59:58.123456", list[0].lastSeenAt)
        assertNull(list[1].lastSeenAt)
        assertFalse(list[1].enabled)
    }

    @Test
    fun unknownFieldsIgnored() {
        val body = """[{"name":"x","enrolled_at":"2026-10-02T08:00:00","last_seen_at":null,"enabled":true,"agent_version":"1.2"}]"""
        assertEquals("x", WireJson.decodeFromString<List<OwnedDevice>>(body).single().name)
    }

    @Test
    fun enrollRequestOmitsMissingName() {
        assertEquals(
            """{"token":"t","agent_version":"android-0.1.0"}""",
            WireJson.encodeToString(EnrollRequest("t", "android-0.1.0")),
        )
        assertEquals(
            """{"token":"t","agent_version":"android-0.1.0","requested_name":"phone"}""",
            WireJson.encodeToString(EnrollRequest("t", "android-0.1.0", "phone")),
        )
    }

    @Test
    fun errorDetail() {
        assertEquals(
            "this token has no fixed name -- pass requested_name",
            errorDetail("""{"detail":"this token has no fixed name -- pass requested_name"}"""),
        )
        // Validation errors (422) carry a list: shown as the raw body.
        val raw = """{"detail":[{"loc":["body","token"],"msg":"Field required"}]}"""
        assertEquals(raw, errorDetail(raw))
        assertEquals("Bad Gateway", errorDetail("Bad Gateway"))
    }
}
