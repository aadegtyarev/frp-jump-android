package ru.adsrv.frpjump.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState

class CardTapTest {
    private val connection = Connection("c1", "wb", 1883, PortKind.MQTT)
    private val tunnel = Tunnel("wb", 1883, "g1", 40000, VisitorState(TunnelStatus.RELAYED))

    private fun row(tunnel: Tunnel? = null, pending: PendingOp? = null, error: String? = null) =
        ConnectionRow("p1", connection, saved = true, tunnel = tunnel, pending = pending, error = error)

    @Test
    fun `up - the main action`() {
        assertEquals(CardTap.MAIN, cardTap(row(tunnel)))
    }

    @Test
    fun `down, connecting or failed - connect, then the main action`() {
        assertEquals(CardTap.CONNECT_THEN_MAIN, cardTap(row()))
        assertEquals(CardTap.CONNECT_THEN_MAIN, cardTap(row(pending = PendingOp.CONNECTING)))
        assertEquals(CardTap.CONNECT_THEN_MAIN, cardTap(row(error = "refused")))
    }

    @Test
    fun `being disconnected - nothing`() {
        assertEquals(CardTap.NONE, cardTap(row(pending = PendingOp.DISCONNECTING)))
        assertEquals("a tap on its disabled power button is not an open", CardTap.NONE, cardTap(row(tunnel, PendingOp.DISCONNECTING)))
    }
}
