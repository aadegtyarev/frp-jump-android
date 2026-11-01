package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.ports.LocalPortAllocator
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.ssh.MemoryStorage

/** Local ports when several profiles share one range, as their tunnel managers plan them. */
class ProfilePortsTest {
    private val storage = MemoryStorage()
    private val allocator = LocalPortAllocator(40000..40009) { true }

    private fun grant(id: String, device: String, port: Int) = ConsumedGrant(id, "s", "svc", "tcp", device, port)

    /** What a profile's [TunnelManager] does on each sync. */
    private fun plan(profile: String, vararg grants: ConsumedGrant, store: LocalPortStore = LocalPortStore(storage)) =
        store.update { ports -> planTunnels(profile, grants.toList(), ports, allocator, emptySet()) }.associate {
            it.grant.exposerDeviceName + ":" + it.grant.targetPort to it.localPort
        }

    @Test
    fun `two profiles never get the same local port, even for a device of the same name`() {
        val work = plan("work", grant("g1", "wb", 80))
        val home = plan("home", grant("g2", "wb", 80))
        assertNotEquals(work.getValue("wb:80"), home.getValue("wb:80"))
    }

    @Test
    fun `each profile keeps its ports across restarts`() {
        val work = plan("work", grant("g1", "wb", 80), grant("g2", "wb", 22))
        val home = plan("home", grant("g3", "wb", 80))
        // A new process: fresh stores over the same file, grants issued anew, in another order.
        assertEquals(home, plan("home", grant("g9", "wb", 80), store = LocalPortStore(storage)))
        assertEquals(work, plan("work", grant("g8", "wb", 22), grant("g7", "wb", 80), store = LocalPortStore(storage)))
    }

    @Test
    fun `forgetting a profile frees only its ports`() {
        plan("work", grant("g1", "wb", 80))
        val home = plan("home", grant("g2", "wb", 80))
        LocalPortStore(storage).removeProfile("work")
        assertEquals(mapOf("home/wb:80" to home.getValue("wb:80")), LocalPortStore(storage).load())
    }

    @Test
    fun `v1 ports become the migrated profile's, once`() {
        val v1 = mapOf("wb:80" to 40002, "wb:22" to 40001)
        val migrated = LocalPortStore.migrateLegacy(v1, "id1")
        assertEquals(mapOf("id1/wb:80" to 40002, "id1/wb:22" to 40001), migrated)
        assertEquals(migrated, LocalPortStore.migrateLegacy(migrated, "id1"))
    }
}
