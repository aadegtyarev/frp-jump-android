package ru.adsrv.frpjump.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.VisitorState

/** The Tunnels tab's filter "only on", its count and the scope search with the "In all" line. */
class TunnelsViewTest {
    private fun tunnel(device: String, port: Int) = Tunnel(device, port, "g-$device-$port", 40000 + port, VisitorState(TunnelStatus.DIRECT))
    private val kitchenSsh = Connection("1", "wb-kitchen", 22, PortKind.SSH, customName = "shell")
    private val kitchenWeb = Connection("2", "wb-kitchen", 8080, PortKind.WEB, customName = "z2m")
    private val boiler = Connection("3", "Boiler", 80, PortKind.WEB, customName = "panel")
    private val attic = Connection("4", "attic", 22, PortKind.SSH, customName = "attic shell")
    private fun label(row: ConnectionRow) = row.connection.customName ?: row.connection.kind.name

    private fun groups(vararg up: Tunnel) =
        groupByDevice(connectionRows("p1", listOf(kitchenSsh, kitchenWeb, boiler, attic), TunnelsState(tunnels = up.toList())), emptyMap())

    private fun names(groups: List<DeviceGroup>) = groups.flatMap { g -> g.rows.map { it.connection.id } }

    @Test fun `the count is the tunnels up or coming up plus those being restored`() {
        assertEquals(0, onTunnelCount(groups(), 0))
        assertEquals(2, onTunnelCount(groups(tunnel("wb-kitchen", 22), tunnel("Boiler", 80)), 0))
        assertEquals(5, onTunnelCount(groups(tunnel("wb-kitchen", 22), tunnel("Boiler", 80)), 3))
        assertEquals(3, onTunnelCount(groups(), 3))
        assertEquals(0, onTunnelCount(groups(), -1))
        // A tunnel no saved connection names counts too.
        assertEquals(1, onTunnelCount(groups(tunnel("router", 9000)), 0))
    }

    @Test fun `on shows only the rows that are on, off shows all`() {
        val g = groups(tunnel("wb-kitchen", 22), tunnel("Boiler", 80))
        val on = tunnelsView(g, onlyOn = true, query = "", label = ::label)
        assertTrue(on.filtered)
        assertEquals(listOf("3", "1"), names(on.groups))
        assertNull(on.inAll)
        val off = tunnelsView(g, onlyOn = false, query = "", label = ::label)
        assertFalse(off.filtered)
        assertEquals(g, off.groups)
    }

    @Test fun `with nothing on the filter is not in effect and all are listed`() {
        val g = groups()
        val view = tunnelsView(g, onlyOn = true, query = "", label = ::label)
        assertFalse(view.filtered)
        assertEquals(g, view.groups)
    }

    @Test fun `search runs within the scope with today's matching`() {
        val g = groups(tunnel("wb-kitchen", 22), tunnel("Boiler", 80))
        // A device name keeps all its rows of the scope (the one that is on), a connection's name only itself.
        assertEquals(listOf("1"), names(tunnelsView(g, true, " KITCH ", ::label).groups))
        assertEquals(listOf("3"), names(tunnelsView(g, true, "panel", ::label).groups))
        // The same query over all.
        assertEquals(listOf("1", "2"), names(tunnelsView(g, false, "kitch", ::label).groups))
        assertNull(tunnelsView(g, false, "kitch", ::label).inAll)
    }

    @Test fun `a scope that shows nothing but all have matches offers the line with the count`() {
        val g = groups(tunnel("wb-kitchen", 22), tunnel("Boiler", 80))
        val view = tunnelsView(g, true, "attic", ::label)
        assertTrue(view.groups.isEmpty())
        assertEquals(1, view.inAll)
        assertEquals(1, tunnelsView(g, true, "z2m", ::label).inAll)
        // Nothing anywhere: no line.
        val none = tunnelsView(g, true, "printer", ::label)
        assertTrue(none.groups.isEmpty())
        assertNull(none.inAll)
        // The filter off: the search already looks at all, no line.
        assertNull(tunnelsView(g, false, "printer", ::label).inAll)
        // A blank query: no line.
        assertNull(tunnelsView(g, true, "  ", ::label).inAll)
    }

    // Favourites: the flag is on the connection; rows of two profiles for the order across them.
    private fun favGroups(favorites: Set<String>, vararg up: Tunnel): List<DeviceGroup> {
        val all = listOf(kitchenSsh, kitchenWeb, boiler, attic).map { it.copy(favorite = it.id in favorites) }
        return groupByDevice(connectionRows("p1", all, TunnelsState(tunnels = up.toList())), emptyMap())
    }

    private fun favs(view: TunnelsView) = view.favorites.map { it.row.connection.id }

    @Test fun `favourites come first by device name then stored order, and are not listed again below`() {
        val view = tunnelsView(favGroups(setOf("2", "3", "1")), onlyOn = false, query = "", label = ::label)
        // Boiler, then wb-kitchen's rows in stored order (1 before 2); each with its device group.
        assertEquals(listOf("3", "1", "2"), favs(view))
        assertEquals(listOf("Boiler", "wb-kitchen", "wb-kitchen"), view.favorites.map { it.group.name })
        // Only attic is left below: a device whose rows are all favourites has no group of its own.
        assertEquals(listOf("4"), names(view.groups))
        val all = favs(view) + names(view.groups)
        assertEquals(all.distinct(), all)
        assertEquals(setOf("1", "2", "3", "4"), all.toSet())
    }

    @Test fun `a device with one favourite keeps its other rows in its group`() {
        val view = tunnelsView(favGroups(setOf("2")), false, "", ::label)
        assertEquals(listOf("2"), favs(view))
        assertEquals(listOf("4", "3", "1"), names(view.groups))
        assertEquals(listOf("wb-kitchen"), view.groups.filter { it.name == "wb-kitchen" }.map { it.name })
    }

    @Test fun `without favourites there is no first group and the groups are as before`() {
        val g = favGroups(emptySet())
        val view = tunnelsView(g, false, "", ::label)
        assertTrue(view.favorites.isEmpty())
        assertEquals(g, view.groups)
    }

    @Test fun `the order of the favourites does not follow the tunnels' state`() {
        val before = favs(tunnelsView(favGroups(setOf("1", "3")), false, "", ::label))
        val up = favs(tunnelsView(favGroups(setOf("1", "3"), tunnel("wb-kitchen", 22)), false, "", ::label))
        assertEquals(before, up)
        assertEquals(listOf("3", "1"), up)
    }

    @Test fun `with the filter on only the favourites that are on appear in the first group`() {
        val g = favGroups(setOf("1", "3"), tunnel("wb-kitchen", 22), tunnel("wb-kitchen", 8080))
        val view = tunnelsView(g, onlyOn = true, query = "", label = ::label)
        assertTrue(view.filtered)
        // 3 (Boiler) is a favourite but off: not there; 2 is on and not a favourite: in its device group.
        assertEquals(listOf("1"), favs(view))
        assertEquals(listOf("2"), names(view.groups))
        // With the filter off the off favourite is back.
        assertEquals(listOf("3", "1"), favs(tunnelsView(g, false, "", ::label)))
    }

    @Test fun `search applies to the favourites too, and nothing shows twice`() {
        val g = favGroups(setOf("1", "3"))
        // A device name keeps its rows: the favourite among them goes to the first group, the rest stay below.
        val kitchen = tunnelsView(g, false, "kitch", ::label)
        assertEquals(listOf("1"), favs(kitchen))
        assertEquals(listOf("2"), names(kitchen.groups))
        assertEquals(listOf("3"), favs(tunnelsView(g, false, "panel", ::label)))
        assertTrue(tunnelsView(g, false, "panel", ::label).groups.isEmpty())
        assertTrue(tunnelsView(g, false, "printer", ::label).isEmpty)
    }

    @Test fun `the In all line counts favourites too and a view with only favourites is not empty`() {
        val g = favGroups(setOf("4"), tunnel("wb-kitchen", 22))
        val view = tunnelsView(g, true, "attic", ::label)
        assertTrue(view.isEmpty)
        assertEquals(1, view.inAll)
        assertFalse(tunnelsView(g, false, "attic", ::label).isEmpty)
    }

    @Test fun `an unsaved tunnel is never a favourite`() {
        val rows = connectionRows("p1", listOf(kitchenSsh.copy(favorite = true)), TunnelsState(tunnels = listOf(tunnel("router", 9000))))
        val view = tunnelsView(groupByDevice(rows, emptyMap()), false, "", ::label)
        assertEquals(listOf("1"), favs(view))
        assertEquals(1, view.groups.sumOf { it.rows.size })
    }
}
