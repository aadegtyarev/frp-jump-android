package ru.adsrv.frpjump.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.agent.TunnelReference
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState

class ReferencedTunnelTest {
    private val connection = Connection("saved", "router", 443, PortKind.WEB, https = true)
    private val row = ConnectionRow("work", connection, saved = true, tunnel = null, pending = null, error = null)

    @Test fun `only the exact saved target matches`() {
        val target = TunnelReference("work", "saved", "router", 443, "WEB", true)
        assertTrue(target.matches(row))
        assertTrue(target.matches(row.copy(connection = connection.copy(customName = "Dashboard"))))
        assertFalse(target.matches(row.copy(profile = "home")))
        assertFalse(target.matches(row.copy(saved = false)))
        assertFalse(target.matches(row.copy(connection = connection.copy(id = "replacement"))))
        assertFalse(target.matches(row.copy(connection = connection.copy(device = "another-router"))))
        assertFalse(target.matches(row.copy(connection = connection.copy(port = 80))))
        assertFalse(target.matches(row.copy(connection = connection.copy(kind = PortKind.TCP))))
        assertFalse(target.matches(row.copy(connection = connection.copy(https = false))))
    }

    @Test fun `only a ready tunnel with a destination opens directly`() {
        for (kind in PortKind.entries) {
            val entry = row.copy(connection = connection.copy(kind = kind))
            assertFalse(entry.opensFromReference())
            for (status in TunnelStatus.entries) {
                val tunnel = Tunnel("router", 443, "grant", 40001, VisitorState(status))
                val active = entry.copy(tunnel = tunnel)
                val ready = status == TunnelStatus.DIRECT || status == TunnelStatus.RELAYED
                assertEquals(ready && kind != PortKind.TCP, active.opensFromReference())
                assertFalse(active.copy(pending = PendingOp.DISCONNECTING).opensFromReference())
            }
        }
    }
}
