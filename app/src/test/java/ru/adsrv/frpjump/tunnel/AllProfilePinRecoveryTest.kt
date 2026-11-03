package ru.adsrv.frpjump.tunnel

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.ConnectionStore
import ru.adsrv.frpjump.connections.tunnelPins
import ru.adsrv.frpjump.ports.*
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.TextStorage

/** Durable connections + shared map + real planner; synthetic grant, no socket/frpc/profile credentials. */
class AllProfilePinRecoveryTest {
    private class MapStorage : TextStorage {
        var value: String? = null
        var failWrite = false
        override fun read() = value
        override fun write(text: String) {
            if (failWrite) throw IOException("Synthetic reservation-map write failure")
            value = text
        }
        override fun delete() { value = null }
    }
    private fun grant(device: String, port: Int) = ConsumedGrant("synthetic-grant", "synthetic-secret", "synthetic-service", "tcp", device, port)
    private fun recoverAfterInterruption(secondWriteFailure: Boolean) {
        val fileA = MemoryStorage()
        val mapFile = MapStorage()
        val a = ConnectionStore(fileA, MemoryStorage())
        val connection = Connection("saved-A", "off", 22, PortKind.SSH, localPort = 40000)
        if (secondWriteFailure) {
            mapFile.failWrite = true
            assertThrows(IOException::class.java) {
                LocalPortStore(mapFile).update { ports ->
                    a.save(connection)
                    reservePins(ports, mapOf(profilePortKey("A", "off", 22) to 40000))
                }
            }
            mapFile.failWrite = false
        } else {
            // Process death immediately after the first durable write, before reservation commit.
            a.save(connection)
        }
        val reloadedA = ConnectionStore(fileA, MemoryStorage())
        assertEquals(40000, reloadedA.connections.value.single().localPort)
        val restartMap = LocalPortStore(mapFile)
        val planned = restartMap.update { ports ->
            planTunnels("B", listOf(grant("auto", 80)), ports, LocalPortAllocator(40000..40009) { true }, emptySet(),
                reservations = PortReservations(setOf("A", "B"), tunnelPins(reloadedA.connections.value).mapKeys { "A/" + it.key }))
        }
        assertNotEquals("B Auto must not steal the saved off-tunnel pin of A", 40000, planned.single().localPort)
        assertEquals(40000, restartMap.load()[profilePortKey("A", "off", 22)])
    }

    @Test fun `another profile cannot allocate a saved off pin after second map write failure`() = recoverAfterInterruption(true)
    @Test fun `another profile cannot allocate a saved off pin after restart between writes`() = recoverAfterInterruption(false)

    @Test fun `an already held Auto port is kept without probing or moving`() {
        val ports = mutableMapOf(profilePortKey("B", "auto", 80) to 40000)
        val planned = planTunnels("B", listOf(grant("auto", 80)), ports, LocalPortAllocator(40000..40009) { false }, setOf(40000))
        assertEquals(40000, planned.single().localPort)
        assertFalse(planned.single().pinned)
    }

    @Test fun `all-profile recovery preserves held Auto and reserves unrelated off pins`() {
        val heldKey = profilePortKey("B", "auto", 80)
        val pinKey = profilePortKey("A", "off", 22)
        val ports = mutableMapOf(heldKey to 40009) // Stale derived data must not move the live40000 allocation.
        val snapshot = PortReservations(setOf("A", "B"), mapOf(pinKey to 40002), mapOf(heldKey to 40000))
        val planned = planTunnels("B", listOf(grant("auto", 80), grant("other", 81)), ports,
            LocalPortAllocator(40000..40009) { it != 40000 }, setOf(40000), reservations = snapshot)
        assertEquals(listOf(40000, 40001), planned.map { it.localPort })
        assertEquals(40002, ports[pinKey])
    }

    @Test fun `inconsistent held or duplicate pins fail before changing any map entry`() {
        val held = profilePortKey("B", "auto", 80)
        val pin = profilePortKey("A", "off", 22)
        val ports = mutableMapOf(held to 40000, "deleted/off:22" to 40005)
        val before = ports.toMap()
        assertThrows(PortReservationConflict::class.java) {
            restorePinReservations("B", ports, PortReservations(setOf("A", "B"), mapOf(pin to 40000), mapOf(held to 40000)))
        }
        assertEquals(before, ports)
        assertThrows(PortReservationConflict::class.java) {
            restorePinReservations("B", ports, PortReservations(setOf("A", "B"), mapOf(pin to 40001, held to 40001)))
        }
        assertEquals(before, ports)
        assertThrows(PortReservationConflict::class.java) {
            restorePinReservations("B", ports, PortReservations(setOf("B"), mapOf(held to 40001), mapOf(held to 40000)))
        }
        assertEquals(before, ports)
    }

    @Test fun `a profile no longer published cannot restore its late reservation or allocate`() {
        val ports = mutableMapOf("A/off:22" to 40000)
        val before = ports.toMap()
        assertThrows(IllegalArgumentException::class.java) {
            restorePinReservations("A", ports, PortReservations(setOf("B"), emptyMap()))
        }
        assertEquals(before, ports)
    }

    @Test fun `deleted profiles are pruned only at planning not from live profile allocations`() {
        val keep = profilePortKey("B", "old", 22)
        val ports = mutableMapOf("deleted/off:22" to 40000, keep to 40005)
        val planned = planTunnels("B", listOf(grant("auto", 80)), ports, LocalPortAllocator(40000..40009) { true }, emptySet(),
            reservations = PortReservations(setOf("B"), emptyMap()))
        assertEquals(40000, planned.single().localPort)
        assertEquals(40005, ports[keep])
        assertFalse(ports.containsKey("deleted/off:22"))
    }

    @Test fun `global saved pin also drives its own fixed visitor without a separate own map`() {
        val key = profilePortKey("A", "off", 22)
        val plan = planTunnels("A", listOf(grant("off", 22)), mutableMapOf(), LocalPortAllocator(40000..40009) { false }, emptySet(),
            reservations = PortReservations(setOf("A"), mapOf(key to 40000)))
        assertEquals(40000, plan.single().localPort)
        assertTrue(plan.single().pinned)
    }

    @Test fun `concurrent profile plans keep the recovered off pin and allocate distinct Auto ports`() {
        val store = LocalPortStore(MapStorage())
        val snapshot = PortReservations(setOf("A", "B", "C"), mapOf(profilePortKey("A", "off", 22) to 40000))
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val futures = listOf("B", "C").map { profile -> pool.submit<Int> {
                check(start.await(5, java.util.concurrent.TimeUnit.SECONDS))
                store.update { ports ->
                    planTunnels(profile, listOf(grant("auto", 80)), ports, LocalPortAllocator(40000..40009) { true }, emptySet(),
                        reservations = snapshot).single().localPort
                }
            } }
            start.countDown()
            assertEquals(setOf(40001, 40002), futures.map { it.get(5, java.util.concurrent.TimeUnit.SECONDS) }.toSet())
            assertEquals(40000, store.load()[profilePortKey("A", "off", 22)])
        } finally { start.countDown(); pool.shutdownNow() }
    }

    @Test fun `an own pin remains fixed even if a probe would reject it`() {
        val connection = Connection("saved-A", "off", 22, PortKind.SSH, localPort = 40000)
        val planned = planTunnels("A", listOf(grant("off", 22)), mutableMapOf(), LocalPortAllocator(40000..40009) { false }, emptySet(),
            pinned = tunnelPins(listOf(connection)))
        assertEquals(40000, planned.single().localPort)
        assertTrue(planned.single().pinned)
    }
}
