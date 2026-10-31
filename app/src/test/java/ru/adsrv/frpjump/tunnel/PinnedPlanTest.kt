package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.ports.LocalPortAllocator
import ru.adsrv.frpjump.ports.reservePins

/** Pinned local ports in the plan: used as they are, never handed to another tunnel of any profile. */
class PinnedPlanTest {
    private val busy = mutableSetOf<Int>()
    private val allocator = LocalPortAllocator(40000..40002) { it !in busy }

    private fun grant(id: String, device: String, port: Int) = ConsumedGrant(id, "s-$id", "svc-$device-$port", "tcp", device, port)

    @Test
    fun `a pinned tunnel gets its pin, also outside the range and when it looks busy`() {
        busy += 18080
        val ports = mutableMapOf<String, Int>()
        val plan = planTunnels("p1", listOf(grant("g1", "llm", 8080)), ports, allocator, emptySet(), pinned = mapOf("llm:8080" to 18080))
        assertEquals(PlannedTunnel(grant("g1", "llm", 8080), 18080, pinned = true), plan.single())
        assertEquals(18080, ports["p1/llm:8080"])
    }

    @Test
    fun `a pin is reserved while its tunnel is off, so auto tunnels never get it`() {
        val ports = mutableMapOf<String, Int>()
        // Pinned inside the range, not connected now.
        val plan = planTunnels("p1", listOf(grant("g1", "wb", 80), grant("g2", "wb", 22)), ports, allocator, emptySet(), pinned = mapOf("llm:8080" to 40000))
        assertTrue(plan.none { it.localPort == 40000 })
        assertEquals(40000, ports["p1/llm:8080"])
    }

    @Test
    fun `another profile's planning skips a pin kept in the shared map`() {
        val ports = mutableMapOf<String, Int>()
        reservePins(ports, mapOf("p1/llm:8080" to 40000))
        val plan = planTunnels("p2", listOf(grant("g9", "wb", 80)), ports, allocator, emptySet())
        assertEquals(40001, plan.single().localPort)
    }

    @Test
    fun `pinning takes the port from an auto tunnel kept there, which moves on its next plan`() {
        val ports = mutableMapOf("p2/wb:80" to 40000)
        reservePins(ports, mapOf("p1/llm:8080" to 40000))
        assertEquals(mapOf("p1/llm:8080" to 40000), ports)
        val plan = planTunnels("p2", listOf(grant("g9", "wb", 80)), ports, allocator, emptySet())
        assertEquals(40001, plan.single().localPort)
    }

    @Test
    fun `auto tunnels are not pinned`() {
        val plan = planTunnels("p1", listOf(grant("g1", "wb", 80)), mutableMapOf(), allocator, emptySet())
        assertEquals(false, plan.single().pinned)
    }
}
