package ru.adsrv.frpjump.direct

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.tunnel.DirectMode
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState

class DirectOffersTest {
    private val offers = DirectOffers()

    private fun tunnel(state: VisitorState, direct: DirectMode = DirectMode.ON, grant: String = "g1", port: Int = 80) =
        Tunnel("wb", port, grant, 40000 + port, state, direct)

    private val failed = VisitorState(TunnelStatus.RELAYED, directFailed = true)

    private fun offered(vararg tunnels: Tunnel) =
        offers.offers(mapOf("work" to tunnels.toList())) { _, t -> if (t.port == 80) "homeui" else "Port ${t.port}" }

    @Test
    fun `a failed direct attempt is offered, named as its connection`() {
        assertEquals(listOf(DirectOffer("work", "wb", 80, "g1", "homeui")), offered(tunnel(failed)))
        assertEquals("work/wb/80", offered(tunnel(failed)).single().key)
    }

    @Test
    fun `trying, direct, off, kept or through the server by choice are not offered`() {
        assertEquals(emptyList<DirectOffer>(), offered(tunnel(VisitorState(TunnelStatus.TRYING))))
        assertEquals(emptyList<DirectOffer>(), offered(tunnel(VisitorState(TunnelStatus.DIRECT))))
        assertEquals(emptyList<DirectOffer>(), offered(tunnel(VisitorState(TunnelStatus.RELAYED), DirectMode.OFF)))
        assertEquals(emptyList<DirectOffer>(), offered(tunnel(VisitorState(TunnelStatus.RELAYED), DirectMode.KEPT)))
        assertEquals(emptyList<DirectOffer>(), offered(tunnel(VisitorState(TunnelStatus.FAILED, "offline"))))
    }

    @Test
    fun `a dismissed offer stays away until the next failure`() {
        offered(tunnel(failed))
        offers.dismiss("g1")
        assertEquals(emptyList<DirectOffer>(), offered(tunnel(failed)))
        // Retried (or went direct and dropped): a new episode.
        offered(tunnel(VisitorState(TunnelStatus.TRYING)))
        assertEquals(1, offered(tunnel(failed)).size)
    }

    @Test
    fun `dismissing one tunnel leaves the other offered`() {
        offers.dismiss("g1")
        assertEquals(listOf("g2"), offered(tunnel(failed), tunnel(failed, grant = "g2", port = 22)).map { it.grantId })
    }
}
