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
}
