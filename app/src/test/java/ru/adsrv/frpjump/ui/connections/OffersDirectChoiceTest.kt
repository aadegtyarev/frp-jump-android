package ru.adsrv.frpjump.ui.connections

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.DirectMode
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState

/** The card's state pill is a button only where Retry direct / Keep through the server mean something. */
class OffersDirectChoiceTest {
    private fun row(status: TunnelStatus, direct: DirectMode = DirectMode.ON, directFailed: Boolean = false, pending: PendingOp? = null, tunnel: Boolean = true) =
        ConnectionRow(
            profile = "work",
            connection = Connection(id = "c1", device = "wb", port = 80, kind = PortKind.WEB),
            saved = true,
            tunnel = if (tunnel) Tunnel("wb", 80, "g", 40080, VisitorState(status, directFailed = directFailed), direct) else null,
            pending = pending,
            error = null,
        )

    @Test
    fun `offered when direct failed, and when kept through the server`() {
        assertTrue(offersDirectChoice(row(TunnelStatus.RELAYED, DirectMode.ON, directFailed = true)))
        assertTrue(offersDirectChoice(row(TunnelStatus.RELAYED, DirectMode.KEPT)))
    }

    @Test
    fun `not offered otherwise`() {
        assertFalse("off", offersDirectChoice(row(TunnelStatus.RELAYED, DirectMode.OFF)))
        assertFalse("trying", offersDirectChoice(row(TunnelStatus.TRYING)))
        assertFalse("direct", offersDirectChoice(row(TunnelStatus.DIRECT)))
        assertFalse("failed", offersDirectChoice(row(TunnelStatus.FAILED)))
        assertFalse("pending", offersDirectChoice(row(TunnelStatus.RELAYED, directFailed = true, pending = PendingOp.DISCONNECTING)))
        assertFalse("relayed, direct not failed", offersDirectChoice(row(TunnelStatus.RELAYED)))
        assertFalse("no tunnel", offersDirectChoice(row(TunnelStatus.RELAYED, tunnel = false)))
    }
}
