package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Expected text is what the Linux agent writes: frp-jump's
 * `build_frpc_config` (consumer side) dumped with `tomli_w`, for device
 * `dev-1`, token `tok`, grants g1 -> 40000 and g2 -> 40001, fallback 500 ms.
 */
class FrpcConfigTest {
    private val tls = TlsFiles(certFile = "/d/tls.crt", keyFile = "/d/tls.key", caFile = "/d/ca.crt")
    private val visitors = listOf(VisitorSpec("g1", "s1", 40000), VisitorSpec("g2", "s2", 40001))

    private fun render(relayOnly: Boolean, logLevel: String = "warn", plainLog: Boolean = false) = Toml.write(
        buildFrpcConfig(
            deviceId = "dev-1",
            apiToken = "tok",
            serverAddr = "relay.example.com",
            serverPort = 7000,
            tls = tls,
            visitors = visitors,
            relayOnly = relayOnly,
            logLevel = logLevel,
            plainLog = plainLog,
        ),
    )

    @Test
    fun `p2p config matches the agent's`() {
        assertEquals(HEADER + P2P_VISITORS, render(relayOnly = false))
    }

    @Test
    fun `relay-only config matches the agent's disable_p2p`() {
        assertEquals(HEADER + RELAY_VISITORS, render(relayOnly = true))
    }

    @Test
    fun `the phone logs at info without colours`() {
        val text = render(relayOnly = false, logLevel = "info", plainLog = true)
        assertTrue(text.contains("[log]\nlevel = \"info\"\ndisablePrintColor = true\n"))
        assertEquals(HEADER + P2P_VISITORS, text.replace("level = \"info\"\ndisablePrintColor = true", "level = \"warn\""))
    }

    @Test
    fun `no grants, no visitors table`() {
        val text = Toml.write(buildFrpcConfig("dev-1", "tok", "relay.example.com", 7000, tls, emptyList()))
        assertTrue(!text.contains("visitors"))
    }

    @Test
    fun `strings are escaped`() {
        assertEquals("a = \"x\\\"y\\\\z\\n\"\n", Toml.write(mapOf("a" to "x\"y\\z\n")))
    }

    private companion object {
        val HEADER = """
            serverAddr = "relay.example.com"
            serverPort = 7000
            loginFailExit = false

            [transport]
            heartbeatInterval = 5
            heartbeatTimeout = 30

            [transport.tls]
            certFile = "/d/tls.crt"
            keyFile = "/d/tls.key"
            trustedCaFile = "/d/ca.crt"

            [log]
            level = "warn"

            [metadatas]
            frp_jump_device = "dev-1"
            frp_jump_token = "tok"

        """.trimIndent() + "\n"

        val P2P_VISITORS = """
            [[visitors]]
            name = "g1-stcp-visitor"
            type = "stcp"
            serverName = "g1-stcp"
            secretKey = "s1"
            bindPort = -1

            [visitors.transport]
            useEncryption = true

            [[visitors]]
            name = "g1-xtcp-visitor"
            type = "xtcp"
            serverName = "g1-xtcp"
            secretKey = "s1"
            bindAddr = "127.0.0.1"
            bindPort = 40000
            fallbackTo = "g1-stcp-visitor"
            fallbackTimeoutMs = 500

            [visitors.transport]
            useEncryption = true

            [[visitors]]
            name = "g2-stcp-visitor"
            type = "stcp"
            serverName = "g2-stcp"
            secretKey = "s2"
            bindPort = -1

            [visitors.transport]
            useEncryption = true

            [[visitors]]
            name = "g2-xtcp-visitor"
            type = "xtcp"
            serverName = "g2-xtcp"
            secretKey = "s2"
            bindAddr = "127.0.0.1"
            bindPort = 40001
            fallbackTo = "g2-stcp-visitor"
            fallbackTimeoutMs = 500

            [visitors.transport]
            useEncryption = true
        """.trimIndent() + "\n"

        val RELAY_VISITORS = """
            [[visitors]]
            name = "g1-stcp-visitor"
            type = "stcp"
            serverName = "g1-stcp"
            secretKey = "s1"
            bindAddr = "127.0.0.1"
            bindPort = 40000

            [visitors.transport]
            useEncryption = true

            [[visitors]]
            name = "g2-stcp-visitor"
            type = "stcp"
            serverName = "g2-stcp"
            secretKey = "s2"
            bindAddr = "127.0.0.1"
            bindPort = 40001

            [visitors.transport]
            useEncryption = true
        """.trimIndent() + "\n"
    }

    @Test
    fun `the admin API listens on loopback with its own credentials`() {
        val text = Toml.write(
            buildFrpcConfig(
                deviceId = "dev-1",
                apiToken = "tok",
                serverAddr = "relay.example.com",
                serverPort = 7000,
                tls = tls,
                visitors = visitors,
                admin = FrpcAdmin(41234, "u", "p"),
            ),
        )
        assertTrue(text, text.contains("[webServer]\naddr = \"127.0.0.1\"\nport = 41234\nuser = \"u\"\npassword = \"p\"\n"))
    }
}
