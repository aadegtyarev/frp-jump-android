package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpcLaunchTest {
    private fun launch(
        visitors: List<VisitorSpec>,
        relayOnly: Boolean = false,
        connection: String = "relay\\ntok",
        keyPem: String = "key",
    ) = FrpcLaunch({ _, _ -> "" }, "cert", keyPem, "ca", visitors, relayOnly, connection)

    private val g1 = VisitorSpec("g1", "s1", 40000)
    private val g2 = VisitorSpec("g2", "s2", 40001)

    @Test
    fun `adding or removing a tunnel is a reload`() {
        assertFalse(launch(listOf(g1, g2)).needsRestartFrom(launch(listOf(g1))))
        assertFalse(launch(listOf(g1)).needsRestartFrom(launch(listOf(g1, g2))))
    }

    @Test
    fun `server, token, TLS or relay-only changes need a restart`() {
        val old = launch(listOf(g1))
        assertTrue(launch(listOf(g1), connection = "other").needsRestartFrom(old))
        assertTrue(launch(listOf(g1), keyPem = "new key").needsRestartFrom(old))
        assertTrue(launch(listOf(g1), relayOnly = true).needsRestartFrom(old))
    }

    @Test
    fun `only new or changed tunnels are restarted by a reload`() {
        val old = launch(listOf(g1))
        assertEquals(listOf("g2"), launch(listOf(g1, g2)).restartedSince(old))
        assertEquals(listOf("g1"), launch(listOf(g1.copy(localPort = 40005))).restartedSince(old))
        assertEquals(emptyList<String>(), launch(listOf(g1)).restartedSince(old))
    }
}
