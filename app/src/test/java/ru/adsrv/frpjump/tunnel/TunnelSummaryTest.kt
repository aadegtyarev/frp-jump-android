package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TunnelSummaryTest {
    @Test
    fun `tunnels of one profile say only the count`() {
        val summary = tunnelSummary(mapOf("work" to 3))
        assertEquals(TunnelSummary(3, 1), summary)
        assertFalse(summary.namesProfiles)
    }

    @Test
    fun `tunnels in several profiles name how many profiles`() {
        val summary = tunnelSummary(mapOf("work" to 2, "home" to 1, "idle" to 0))
        assertEquals(TunnelSummary(3, 2), summary)
        assertTrue(summary.namesProfiles)
    }

    @Test
    fun `no tunnels, no profiles`() {
        assertEquals(TunnelSummary(0, 0), tunnelSummary(emptyMap()))
    }

    @Test
    fun `just restarted, the record not read yet, restoring without a count`() {
        assertEquals(TunnelsTitle.RestoringUnknown, tunnelsTitle(TunnelSummary.of(emptyMap(), restoring = null)))
    }

    @Test
    fun `restoring with a count until a tunnel is up`() {
        assertEquals(TunnelsTitle.Restoring(2), tunnelsTitle(TunnelSummary.of(emptyMap(), restoring = 2)))
        val up = TunnelSummary.of(mapOf("work" to 1), restoring = 1)
        assertEquals(TunnelsTitle.Up(up), tunnelsTitle(up))
        val unknown = TunnelSummary.of(mapOf("work" to 1), restoring = null)
        assertEquals(TunnelsTitle.Up(unknown), tunnelsTitle(unknown))
    }

    @Test
    fun `the notification title adds open terminal sessions while tunnels are up`() {
        val words = { n: Int -> "$n terminal sessions" }
        val up = TunnelsTitle.Up(TunnelSummary(2, 1))
        assertEquals("2 tunnels · 3 terminal sessions", notificationTitle("2 tunnels", up, 3, words))
        assertEquals("2 tunnels", notificationTitle("2 tunnels", up, 0, words))
        // Restoring: no sessions can be on tunnels that are not up yet.
        assertEquals("Restoring 2 tunnels…", notificationTitle("Restoring 2 tunnels…", TunnelsTitle.Restoring(2), 1, words))
    }
}
