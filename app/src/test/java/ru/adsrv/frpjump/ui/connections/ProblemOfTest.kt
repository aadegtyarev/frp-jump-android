package ru.adsrv.frpjump.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.R
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionRow
import ru.adsrv.frpjump.errors.DevicePortNotOpen
import ru.adsrv.frpjump.errors.DomainError
import ru.adsrv.frpjump.locale.Texts
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelManager
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState
import ru.adsrv.frpjump.ui.userText

/** What a row says went wrong: the last operation's typed error, else the tunnel's own state, worded now. */
class ProblemOfTest {
    private val taken = "listen tcp 127.0.0.1:18080: bind: address already in use"
    private val pinnedText = Texts.get(R.string.local_port_taken_pinned, 18080, TunnelManager.PINNED_RETRY_MS / 1000)
    private val missingText = DevicePortNotOpen(80).userText()

    private fun row(state: VisitorState? = null, pinned: Boolean = false, error: DomainError? = null) = ConnectionRow(
        "p", Connection("c1", "wb", 80, PortKind.WEB, "c"), saved = true,
        tunnel = state?.let { Tunnel("wb", 80, "g1", 18080, it, pinned = pinned) }, pending = null, error = error,
    )

    @Test fun `the last operation's error wins over the tunnel's state`() {
        val failed = VisitorState(TunnelStatus.FAILED, taken, deviceMissing = true, pinnedPortTaken = true)
        assertEquals("boom", problemOf(row(failed, pinned = true, error = DomainError.ServerError("boom"))))
        assertEquals(DomainError.NotListed.userText(), problemOf(row(error = DomainError.NotListed)))
        assertEquals(DomainError.NotListed.userText(), problemOf(row(VisitorState(TunnelStatus.RELAYED), error = DomainError.NotListed)))
    }

    @Test fun `a tunnel that has not failed says nothing, whatever its flags and detail`() {
        for (status in TunnelStatus.entries - TunnelStatus.FAILED) {
            val state = VisitorState(status, "raw frpc text", deviceMissing = true, pinnedPortTaken = true)
            assertNull("$status", problemOf(row(state, pinned = true)))
        }
        assertNull(problemOf(row()))
    }

    @Test fun `a failed tunnel with no flag says frpc's own text, or nothing without one`() {
        assertEquals("start error: boom", problemOf(row(VisitorState(TunnelStatus.FAILED, "start error: boom"))))
        assertNull(problemOf(row(VisitorState(TunnelStatus.FAILED))))
    }

    @Test fun `an address already in use on a port that is not pinned keeps frpc's raw text`() {
        assertEquals(taken, problemOf(row(VisitorState(TunnelStatus.FAILED, taken), pinned = false)))
        // Pinned, but not flagged by the manager: still the raw text.
        assertEquals(taken, problemOf(row(VisitorState(TunnelStatus.FAILED, taken), pinned = true)))
    }

    @Test fun `a pinned port another app holds says so, with the port and the retry time`() {
        assertEquals(pinnedText, problemOf(row(VisitorState(TunnelStatus.FAILED, taken, pinnedPortTaken = true), pinned = true)))
    }

    @Test fun `a device that has not opened its port says which port`() {
        assertEquals(missingText, problemOf(row(VisitorState(TunnelStatus.FAILED, deviceMissing = true))))
    }

    @Test fun `when both flags are set the pinned port is said first`() {
        assertEquals(pinnedText, problemOf(row(VisitorState(TunnelStatus.FAILED, deviceMissing = true, pinnedPortTaken = true), pinned = true)))
    }
}
