package ru.adsrv.frpjump.direct

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.tunnel.DirectMode
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState

class DirectOffersTest {
    private val memory = ProcessOfferMemory()
    private val offers = DirectOffers(delayMs = 3_000, memory = memory)
    private var now = 0L

    private fun tunnel(state: VisitorState, direct: DirectMode = DirectMode.ON, port: Int = 80, device: String = "wb") =
        Tunnel(device, port, "$device-$port", 40000 + port, state, direct)

    private val failed = VisitorState(TunnelStatus.RELAYED, directFailed = true)

    private fun offered(vararg tunnels: Tunnel) =
        offers.offers(mapOf("work" to tunnels.toList()), now) { _, t -> if (t.port == 80) "Web, homeui" else "SSH" }

    /** Offered once the failure has lasted the delay. */
    private fun settled(vararg tunnels: Tunnel): List<DirectOffer> {
        offered(*tunnels)
        now += 3_000
        return offered(*tunnels).offers
    }

    @Test
    fun `one offer per device, naming its failed connections`() {
        val list = settled(tunnel(failed), tunnel(failed, port = 22), tunnel(failed, device = "beelink"))
        assertEquals(
            listOf(DirectOffer("work", "wb", "Web, homeui, SSH"), DirectOffer("work", "beelink", "Web, homeui")),
            list,
        )
        assertEquals("work/wb", list.first().key)
    }

    @Test
    fun `offered only after 3 s of failure, and asks to be asked again then`() {
        now = 10_000
        val first = offered(tunnel(failed))
        assertEquals(emptyList<DirectOffer>(), first.offers)
        assertEquals(3_000L, first.recheckInMs)
        now += 2_000
        assertEquals(1_000L, offered(tunnel(failed)).recheckInMs)
        now += 1_000
        assertEquals(OfferList(listOf(DirectOffer("work", "wb", "Web, homeui")), null), offered(tunnel(failed)))
    }

    @Test
    fun `a punch confirmed within the delay never shows`() {
        offered(tunnel(failed))
        now += 2_000
        assertEquals(OfferList(emptyList(), null), offered(tunnel(VisitorState(TunnelStatus.DIRECT))))
        now += 2_000
        assertEquals("the clock starts again", emptyList<DirectOffer>(), offered(tunnel(failed)).offers)
    }

    @Test
    fun `trying, direct, off, kept or failed by the relay are not offered`() {
        assertEquals(emptyList<DirectOffer>(), settled(tunnel(VisitorState(TunnelStatus.TRYING))))
        assertEquals(emptyList<DirectOffer>(), settled(tunnel(VisitorState(TunnelStatus.DIRECT))))
        assertEquals(emptyList<DirectOffer>(), settled(tunnel(VisitorState(TunnelStatus.RELAYED), DirectMode.OFF)))
        assertEquals(emptyList<DirectOffer>(), settled(tunnel(VisitorState(TunnelStatus.RELAYED), DirectMode.KEPT)))
        assertEquals(emptyList<DirectOffer>(), settled(tunnel(VisitorState(TunnelStatus.FAILED, "offline"))))
    }

    @Test
    fun `a dismissed offer stays away until the device has no failed tunnel`() {
        settled(tunnel(failed))
        offers.dismiss("work/wb")
        assertEquals(emptyList<DirectOffer>(), settled(tunnel(failed), tunnel(failed, port = 22)))
        // Retried: a new episode.
        offers.retried("work", "wb")
        offered(tunnel(VisitorState(TunnelStatus.TRYING)))
        assertEquals(1, settled(tunnel(failed)).size)
    }

    @Test
    fun `dismissing one device leaves the other offered`() {
        offers.dismiss("work/wb")
        assertEquals(listOf("beelink"), settled(tunnel(failed), tunnel(failed, device = "beelink")).map { it.device })
    }

    // Offers made before a restart (the memory survives, the rest is new).

    private fun restarted() = DirectOffers(delayMs = 3_000, memory = memory)

    private fun offeredOn(o: DirectOffers, network: String, restoring: Set<String> = emptySet(), tunnels: Map<String, List<Tunnel>>) =
        o.offers(tunnels, now, network, restoring) { _, _ -> "Web" }.offers

    private fun settledOn(o: DirectOffers, network: String, restoring: Set<String> = emptySet(), tunnels: List<Tunnel>): List<DirectOffer> {
        offeredOn(o, network, restoring, mapOf("work" to tunnels))
        now += 3_000
        return offeredOn(o, network, restoring, mapOf("work" to tunnels))
    }

    @Test
    fun `tunnels brought back after a restart that fail again are not offered again on that network`() {
        assertEquals(1, settledOn(offers, "wifi:a", tunnels = listOf(tunnel(failed))).size)
        // Still shown in this process while the failure lasts.
        assertEquals(1, settledOn(offers, "wifi:a", tunnels = listOf(tunnel(failed))).size)
        val after = restarted()
        // Cold start: the profile's tunnels are still to come back.
        offeredOn(after, "wifi:a", setOf("work"), mapOf("work" to emptyList()))
        offeredOn(after, "wifi:a", setOf("work"), mapOf("work" to listOf(tunnel(VisitorState(TunnelStatus.TRYING)))))
        assertEquals(emptyList<DirectOffer>(), settledOn(after, "wifi:a", tunnels = listOf(tunnel(failed))))
        assertEquals("another network is news", 1, settledOn(restarted(), "mobile", tunnels = listOf(tunnel(failed))).size)
    }

    @Test
    fun `going direct or the tunnels going away lets the device be offered again`() {
        settledOn(offers, "wifi:a", tunnels = listOf(tunnel(failed)))
        offeredOn(offers, "wifi:a", tunnels = mapOf("work" to listOf(tunnel(VisitorState(TunnelStatus.DIRECT)))))
        assertEquals("went direct", 1, settledOn(restarted(), "wifi:a", tunnels = listOf(tunnel(failed))).size)

        offeredOn(offers, "wifi:a", tunnels = mapOf("work" to emptyList()))
        assertEquals("disconnected", 1, settledOn(restarted(), "wifi:a", tunnels = listOf(tunnel(failed))).size)
    }

    @Test
    fun `retry direct makes a failure news again`() {
        settledOn(offers, "wifi:a", tunnels = listOf(tunnel(failed)))
        offers.retried("work", "wb")
        assertEquals(1, settledOn(restarted(), "wifi:a", tunnels = listOf(tunnel(failed))).size)
    }
}
