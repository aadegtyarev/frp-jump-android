package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.api.DesiredStateResponse
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.ports.LocalPortAllocator

class VisitorPlanTest {
    private val busy = mutableSetOf<Int>()
    private val allocator = LocalPortAllocator(40000..40999) { it !in busy }

    private fun grant(id: String, device: String, port: Int) =
        ConsumedGrant(id, "secret-$id", "svc-$device-$port", "tcp", device, port)

    @Test
    fun `desired state from the wire becomes one visitor per consumed grant`() {
        val body = """
            {"device_id": "phone-id", "server_addr": "relay", "server_port": 7000, "protocol_version": 1,
             "exposed": [{"grant_id": "x", "secret": "s", "service_name": "svc", "target_port": 8080}],
             "consumed": [
               {"grant_id": "g1", "secret": "s1", "service_name": "svc-a-80", "protocol": "tcp",
                "exposer_device_name": "wb", "target_port": 80},
               {"grant_id": "g2", "secret": "s2", "service_name": "svc-a-22", "protocol": "ssh",
                "exposer_device_name": "wb", "target_port": 22}
             ]}
        """
        val desired = WireJson.decodeFromString<DesiredStateResponse>(body)
        val ports = mutableMapOf("wb:22" to 40500)
        val plan = planTunnels(desired.consumed, ports, allocator, heldByFrpc = emptySet())
        assertEquals(
            listOf(VisitorSpec("g1", "s1", 40000), VisitorSpec("g2", "s2", 40500)),
            plan.map { it.visitor() },
        )
        assertEquals(mapOf("wb:22" to 40500, "wb:80" to 40000), ports)
    }

    @Test
    fun `a new grant for the same device and port reuses the local port`() {
        val ports = mutableMapOf<String, Int>()
        val first = planTunnels(listOf(grant("g1", "wb", 80)), ports, allocator, emptySet())
        // Disconnected, then connected again: the server issues a new grant id.
        val again = planTunnels(listOf(grant("g9", "wb", 80)), ports, allocator, emptySet())
        assertEquals(first.single().localPort, again.single().localPort)
    }

    @Test
    fun `ports the running frpc holds are not probed`() {
        val ports = mutableMapOf("wb:80" to 40000)
        busy += 40000
        val plan = planTunnels(listOf(grant("g1", "wb", 80)), ports, allocator, heldByFrpc = setOf(40000))
        assertEquals(40000, plan.single().localPort)
    }

    @Test
    fun `no consumed grants, no tunnels, ports kept`() {
        val ports = mutableMapOf("wb:80" to 40000)
        assertEquals(emptyList<PlannedTunnel>(), planTunnels(emptyList(), ports, allocator, emptySet()))
        assertEquals(mapOf("wb:80" to 40000), ports)
    }
}
