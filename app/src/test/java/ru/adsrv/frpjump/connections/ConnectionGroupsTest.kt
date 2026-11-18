package ru.adsrv.frpjump.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.devices.DeviceRow
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.VisitorState

class ConnectionGroupsTest {
    private fun device(name: String, online: Boolean, stale: Boolean = false, checking: Boolean = false) =
        DeviceRow(name, online = online, lastSeen = null, disabled = false, stale = stale, checking = checking)
    private fun tunnel(device: String, port: Int) = Tunnel(device, port, "g-$device-$port", 40000 + port, VisitorState(TunnelStatus.DIRECT))

    private val kitchenSsh = Connection("1", "wb-kitchen", 22, PortKind.SSH)
    private val kitchenZ2m = Connection("2", "wb-kitchen", 8080, PortKind.WEB, "z2m")
    private val boiler = Connection("3", "Boiler", 80, PortKind.WEB)
    private val attic = Connection("4", "attic", 22, PortKind.SSH)
    private val router = Connection("5", "router", 80, PortKind.WEB)

    private fun rows(profile: String, connections: List<Connection>, up: List<Tunnel> = emptyList()) =
        connectionRows(profile, connections, TunnelsState(tunnels = up))

    @Test
    fun `devices by name ignoring case, whatever their state`() {
        val rows = rows("p1", listOf(kitchenSsh, kitchenZ2m, boiler, attic, router), up = listOf(tunnel("router", 80)))
        val devices = mapOf("p1" to listOf(device("wb-kitchen", true), device("Boiler", false), device("attic", true), device("router", false)))
        val groups = groupByDevice(rows, devices)
        assertEquals(listOf("attic", "Boiler", "router", "wb-kitchen"), groups.map { it.name })
        // An active group on a device that went offline: still known offline, and not moved.
        assertTrue(groups.single { it.name == "router" }.offline)
    }

    @Test
    fun `a tunnel going up or down and a device going online or offline do not move groups`() {
        val connections = listOf(kitchenSsh, kitchenZ2m, boiler, attic, router)
        val expected = listOf("attic", "Boiler", "router", "wb-kitchen")
        val states = listOf(
            emptyList<Tunnel>() to emptyMap(),
            listOf(tunnel("wb-kitchen", 22)) to mapOf("p1" to listOf(device("wb-kitchen", true), device("attic", false))),
            listOf(tunnel("wb-kitchen", 22), tunnel("router", 80)) to mapOf("p1" to listOf(device("Boiler", true), device("router", true))),
            listOf(tunnel("Boiler", 80)) to mapOf("p1" to listOf(device("wb-kitchen", false, stale = true), device("attic", true, checking = true))),
            emptyList<Tunnel>() to mapOf("p1" to null),
        )
        for ((up, devices) in states) {
            assertEquals(expected, groupByDevice(rows("p1", connections, up), devices).map { it.name })
        }
    }

    @Test
    fun `a device's connections stay together in their stored order, unsaved tunnels after them`() {
        val rows = rows("p1", listOf(kitchenSsh, boiler, kitchenZ2m), up = listOf(tunnel("wb-kitchen", 9000)))
        val kitchen = groupByDevice(rows, emptyMap()).single { it.name == "wb-kitchen" }
        assertEquals(listOf("1", "2"), kitchen.rows.filter { it.saved }.map { it.connection.id })
        assertFalse(kitchen.rows.last().saved)
        assertEquals(9000, kitchen.rows.last().connection.port)
    }

    @Test
    fun `a device not in its profile's list, or the list not read, is not known to be offline`() {
        val rows = rows("p1", listOf(kitchenSsh, boiler))
        val groups = groupByDevice(rows, mapOf("p1" to listOf(device("wb-kitchen", true))))
        val boilerGroup = groups.single { it.name == "Boiler" }
        assertNull(boilerGroup.device)
        assertFalse(boilerGroup.offline)
        assertEquals(listOf("Boiler", "wb-kitchen"), groups.map { it.name })
        assertFalse(groupByDevice(rows, mapOf("p1" to null)).any { it.offline })
    }

    @Test
    fun `devices of the same name in two profiles are two groups, each with its own state`() {
        val rows = rows("p1", listOf(kitchenSsh)) + rows("p2", listOf(kitchenSsh.copy(id = "9")))
        val devices = mapOf("p1" to listOf(device("wb-kitchen", false)), "p2" to listOf(device("wb-kitchen", true)))
        val groups = groupByDevice(rows, devices)
        assertEquals(listOf("p1/wb-kitchen", "p2/wb-kitchen"), groups.map { it.key })
        // Same name: profile order breaks the tie, whatever the state.
        val equal = groupByDevice(rows, emptyMap())
        assertEquals(listOf("p1", "p2"), equal.map { it.profile })
    }

    private val groups = groupByDevice(rows("p1", listOf(kitchenSsh, kitchenZ2m, boiler, attic)), emptyMap())
    private fun label(row: ConnectionRow) = row.connection.customName ?: row.connection.kind.name

    @Test
    fun `a blank query keeps everything`() {
        assertEquals(groups, filterGroups(groups, "  ", ::label))
    }

    @Test
    fun `a device name match keeps all its connections`() {
        val found = filterGroups(groups, " KITCH ", ::label)
        assertEquals(listOf("wb-kitchen"), found.map { it.name })
        assertEquals(2, found.single().rows.size)
    }

    @Test
    fun `a connection name match keeps only that connection under its device`() {
        val found = filterGroups(groups, "z2m", ::label)
        assertEquals(listOf("wb-kitchen"), found.map { it.name })
        assertEquals(listOf("2"), found.single().rows.map { it.connection.id })
        assertEquals(listOf("wb-kitchen", "attic"), filterGroups(groups, "ssh", ::label).map { it.name }.sortedDescending())
    }

    @Test
    fun `a device label match keeps all its connections, compared as Devices search`() {
        val labelled = groupByDevice(rows("p", listOf(kitchenSsh, kitchenZ2m, boiler)), mapOf("p" to listOf(device("wb-kitchen", true).copy(label = "Щиток, ГАРАЖ"))))
        val found = filterGroups(labelled, " гараж ", ::label)
        assertEquals(listOf("wb-kitchen"), found.map { it.name })
        assertEquals(2, found.single().rows.size)
    }

    @Test
    fun `nothing matching gives no groups`() {
        assertEquals(emptyList<DeviceGroup>(), filterGroups(groups, "printer", ::label))
    }

    @Test
    fun `only a fresh offline blocks connecting, an old or unchecked list does not`() {
        assertTrue(knownOffline(device("wb", online = false)))
        assertFalse(knownOffline(device("wb", online = false, stale = true)))
        assertFalse(knownOffline(device("wb", online = false, stale = true, checking = true)))
        assertFalse(knownOffline(device("wb", online = true)))
        assertFalse(knownOffline(null))
        val rows = rows("p1", listOf(kitchenSsh), up = emptyList())
        val stale = groupByDevice(rows, mapOf("p1" to listOf(device("wb-kitchen", online = false, stale = true))))
        assertFalse(stale.single().offline)
    }

    @Test
    fun `offline blocks Connect of a connection that is down, never Disconnect of one up`() {
        val (down, up) = rows("p1", listOf(boiler, router), up = listOf(tunnel("router", 80)))
        assertTrue(connectBlocked(down, offline = true))
        assertFalse(connectBlocked(up, offline = true))
        assertFalse(connectBlocked(down, offline = false))
    }

    @Test
    fun `a card shows the connection's own name only when it says more than the kind`() {
        assertEquals("z2m", connectionTitle(kitchenZ2m, "Web"))
        assertNull(connectionTitle(boiler, "Web"))
        assertNull(connectionTitle(kitchenSsh.copy(customName = "ssh"), "SSH"))
        assertNull(connectionTitle(kitchenSsh.copy(customName = "  "), "SSH"))
        assertEquals("root", connectionTitle(kitchenSsh.copy(customName = " root "), "SSH"))
    }
}
