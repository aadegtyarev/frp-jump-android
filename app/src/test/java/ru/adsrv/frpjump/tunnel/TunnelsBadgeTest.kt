package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.profiles.shownTunnelCounts

/** The launcher badge counts tunnels, the same number the notification title says. */
class TunnelsBadgeTest {
    private fun tunnel(device: String, port: Int, status: TunnelStatus = TunnelStatus.DIRECT) =
        Tunnel(device, port, "g-$device-$port", 40000 + port, VisitorState(status))

    private fun badge(states: Map<String, TunnelsState>, dark: Set<String> = emptySet()) =
        tunnelsBadge(tunnelSummary(shownTunnelCounts(states, dark)))

    @Test
    fun `tunnels of all profiles add up`() {
        val states = mapOf(
            "p1" to TunnelsState(tunnels = listOf(tunnel("a", 22), tunnel("a", 80))),
            "p2" to TunnelsState(tunnels = listOf(tunnel("b", 22))),
        )
        assertEquals(3, badge(states))
    }

    @Test
    fun `a tunnel listed once counts once`() {
        // That connections sharing a device port are one tunnel is proved in ConnectionRowsTest.kt (line 31).
        val states = mapOf("p1" to TunnelsState(tunnels = listOf(tunnel("a", 22))))
        assertEquals(1, badge(states))
    }

    @Test
    fun `a listed tunnel counts in any status, one still being connected does not`() {
        val listed = TunnelStatus.entries.map { tunnel("a", 1000 + it.ordinal, it) }
        val states = mapOf("p1" to TunnelsState(tunnels = listed, pending = mapOf("a:9" to PendingOp.CONNECTING)))
        assertEquals(TunnelStatus.entries.size, badge(states))
    }

    @Test
    fun `none, restoring or disconnecting is zero`() {
        assertEquals(0, badge(emptyMap()))
        assertEquals(0, tunnelsBadge(TunnelSummary.of(emptyMap(), restoring = 3)))
        assertEquals(0, tunnelsBadge(TunnelSummary.of(emptyMap(), restoring = 0).copy(dark = 2)))
    }

    @Test
    fun `the badge says what the title says`() {
        val summary = tunnelSummary(mapOf("p1" to 2, "p2" to 1))
        val up = tunnelsTitle(summary) as TunnelsTitle.Up
        assertEquals(up.summary.tunnels, tunnelsBadge(summary))
        assertEquals(3, tunnelsBadge(summary))
    }
}
