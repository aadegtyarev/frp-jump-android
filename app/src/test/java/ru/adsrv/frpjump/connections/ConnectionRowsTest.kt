package ru.adsrv.frpjump.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.api.ConsumedGrant
import ru.adsrv.frpjump.ports.LocalPortAllocator
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ports.wireProtocolFor
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.tunnel.PendingOp
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.VisitorState
import ru.adsrv.frpjump.tunnel.planTunnels

class ConnectionRowsTest {
    private val root = Connection("1", "wb", 22, PortKind.SSH, "root", user = "root")
    private val admin = Connection("2", "wb", 22, PortKind.SSH, "admin", user = "admin")
    private val ui = Connection("3", "wb", 80, PortKind.WEB, "homeui")
    private val other = Connection("4", "pc", 22, PortKind.SSH, "pc")

    private fun tunnel(device: String, port: Int, local: Int) = Tunnel(device, port, "g-$device-$port", local, VisitorState(TunnelStatus.DIRECT))

    @Test
    fun `connections to the same port share one tunnel, its state and its errors`() {
        val tunnels = TunnelsState(
            tunnels = listOf(tunnel("wb", 22, 40001)),
            pending = mapOf("wb:22" to PendingOp.DISCONNECTING),
            errors = mapOf("wb:22" to "boom"),
        )
        val rows = connectionRows("p1", listOf(root, admin, ui), tunnels)
        val (a, b, c) = rows
        assertEquals(40001, a.tunnel?.localPort)
        assertEquals(a.tunnel, b.tunnel)
        assertEquals(listOf(PendingOp.DISCONNECTING, PendingOp.DISCONNECTING), listOf(a.pending, b.pending))
        assertEquals(listOf("boom", "boom"), listOf(a.error, b.error))
        assertFalse(c.active)
        assertEquals(setOf("p1/1", "p1/2", "p1/3"), rows.map { it.key }.toSet())
    }

    @Test
    fun `a device's card lists only its connections and its unsaved tunnels`() {
        val tunnels = TunnelsState(tunnels = listOf(tunnel("wb", 8080, 40009), tunnel("pc", 3389, 40010)))
        val rows = connectionRows("p1", listOf(root, ui, other), tunnels, device = "wb")
        assertEquals(listOf("1", "3"), rows.filter { it.saved }.map { it.connection.id })
        val unsaved = rows.single { !it.saved }
        assertEquals(8080, unsaved.connection.port)
        assertTrue(unsaved.active)
    }

    @Test
    fun `a connection deleted with Undo on screen is not listed, nor is its tunnel as unsaved`() {
        val tunnels = TunnelsState(tunnels = listOf(tunnel("wb", 80, 40002)))
        val rows = connectionRows("p1", listOf(root, ui), tunnels, hidden = setOf("3"))
        assertEquals(listOf("1"), rows.map { it.connection.id })
    }

    @Test
    fun `on a device's card too a hidden connection's tunnel is not an unsaved row`() {
        val tunnels = TunnelsState(tunnels = listOf(tunnel("wb", 80, 40002)))
        val rows = connectionRows("p1", listOf(root, ui, other), tunnels, device = "wb", hidden = setOf("3"))
        assertEquals(listOf("1"), rows.map { it.connection.id })
        assertTrue(rows.all { it.saved })
    }

    @Test
    fun `deleting two connections of one tunnel disconnects it with the last, not before`() {
        // root and admin share wb:22; each delete checks the list as it is after it.
        assertFalse(disconnectsAfterDelete(root, listOf(admin, ui), up = true))
        assertTrue(disconnectsAfterDelete(admin, listOf(ui), up = true))
        assertFalse(disconnectsAfterDelete(admin, listOf(ui), up = false))
    }

    @Test
    fun `active ones go first, each group keeps its order`() {
        val tunnels = TunnelsState(tunnels = listOf(tunnel("wb", 80, 40002), tunnel("pc", 22, 40003)))
        val (active, saved) = activeFirst(connectionRows("p1", listOf(root, ui, admin, other), tunnels))
        assertEquals(listOf("3", "4"), active.map { it.connection.id })
        assertEquals(listOf("1", "2"), saved.map { it.connection.id })
    }

    @Test
    fun `deleting the last connection of a port takes its tunnel along, not otherwise`() {
        assertFalse(lastOnItsTunnel(root, listOf(root, admin, ui)))
        assertTrue(lastOnItsTunnel(ui, listOf(root, admin, ui)))
        assertTrue(lastOnItsTunnel(other, listOf(root, other)))
    }

    @Test
    fun `two profiles' connections to a device of the same name get different local ports`() {
        val storage = LocalPortStore(MemoryStorage())
        val allocator = LocalPortAllocator(40000..40009) { true }
        fun rowsOf(profile: String, connections: List<Connection>): List<ConnectionRow> {
            val grants = connections.distinctBy { it.port }.map {
                ConsumedGrant("$profile-${it.port}", "s", "svc", wireProtocolFor(it.kind, it.port), it.device, it.port)
            }
            val planned = storage.update { ports -> planTunnels(profile, grants, ports, allocator, emptySet()) }
            val tunnels = planned.map { Tunnel(it.grant.exposerDeviceName, it.grant.targetPort, it.grant.grantId, it.localPort, VisitorState(TunnelStatus.TRYING)) }
            return connectionRows(profile, connections, TunnelsState(tunnels = tunnels))
        }
        val work = rowsOf("work", listOf(root, admin))
        val home = rowsOf("home", listOf(root.copy(id = "9")))
        // One tunnel, one local port for both of work's connections; home's differs.
        assertEquals(work[0].tunnel?.localPort, work[1].tunnel?.localPort)
        assertNotEquals(work[0].tunnel?.localPort, home[0].tunnel?.localPort)
    }

    @Test
    fun `the wire protocol follows the Linux client`() {
        assertEquals("ssh", wireProtocolFor(PortKind.SSH, 8022))
        assertEquals("ssh", wireProtocolFor(PortKind.TCP, 2222))
        assertEquals("tcp", wireProtocolFor(PortKind.WEB, 80))
    }
}
