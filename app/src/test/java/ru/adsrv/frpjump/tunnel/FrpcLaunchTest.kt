package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrpcLaunchTest {
    private fun launch(
        visitors: List<VisitorSpec>,
        connection: String = "relay\\ntok",
        keyPem: String = "key",
    ) = FrpcLaunch({ _, _ -> "" }, "cert", keyPem, "ca", visitors, connection)

    private val g1 = VisitorSpec("g1", "s1", 40000)
    private val g2 = VisitorSpec("g2", "s2", 40001)

    @Test
    fun `adding or removing a tunnel is a reload`() {
        assertFalse(launch(listOf(g1, g2)).needsRestartFrom(launch(listOf(g1))))
        assertFalse(launch(listOf(g1)).needsRestartFrom(launch(listOf(g1, g2))))
    }

    @Test
    fun `server, token or TLS changes need a restart`() {
        val old = launch(listOf(g1))
        assertTrue(launch(listOf(g1), connection = "other").needsRestartFrom(old))
        assertTrue(launch(listOf(g1), keyPem = "new key").needsRestartFrom(old))
    }

    @Test
    fun `switching direct on or off is a reload`() {
        val old = launch(listOf(g1, g2))
        assertFalse(launch(listOf(g1.copy(direct = false), g2)).needsRestartFrom(old))
    }

    @Test
    fun `only new or changed visitors are restarted by a reload`() {
        val old = launch(listOf(g1))
        assertEquals(listOf("g2-stcp-visitor", "g2-xtcp-visitor"), launch(listOf(g1, g2)).restartedSince(old))
        assertEquals(listOf("g1-xtcp-visitor"), launch(listOf(g1.copy(localPort = 40005))).restartedSince(old))
        assertEquals(emptyList<String>(), launch(listOf(g1)).restartedSince(old))
    }

    @Test
    fun `retry direct restarts only the tunnel's xtcp visitor`() {
        val old = launch(listOf(g1, g2))
        assertEquals(listOf("g1-xtcp-visitor-1"), launch(listOf(g1.copy(attempt = 1), g2)).restartedSince(old))
    }

    @Test
    fun `direct off restarts the stcp visitor bound in its place, on again adds the xtcp one`() {
        val on = launch(listOf(g1))
        val off = launch(listOf(g1.copy(direct = false)))
        assertEquals(listOf("g1-stcp-visitor"), off.restartedSince(on))
        assertEquals(listOf("g1-stcp-visitor", "g1-xtcp-visitor"), on.restartedSince(off))
    }
}
