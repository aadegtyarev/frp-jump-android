package ru.adsrv.frpjump.ui.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.connections.DeviceGroup
import ru.adsrv.frpjump.connections.connectionRows
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelRef
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.TunnelsState
import ru.adsrv.frpjump.tunnel.VisitorState

class TunnelScopeTest {
    @Test fun `new tunnel starts as SSH on port 22`() {
        assertEquals(PortKind.SSH, newConnectionDraft().kind)
        assertEquals("22", newConnectionDraft().portText)
    }

    @Test fun `duplicate profile names keep unique identity`() {
        val names = mapOf("a" to "Home", "b" to "Home", "c" to "Work")
        assertEquals("Home · a", profileLabel("a", names))
        assertEquals("Home · b", profileLabel("b", names))
        assertEquals("Work", profileLabel("c", names))
    }

    @Test fun `same device name in different profiles stays separate`() {
        val first = DeviceGroup("a", "router", null, emptyList())
        val second = DeviceGroup("b", "router", null, emptyList())
        assertTrue(inDeviceScope(listOf("a", "router"), first))
        assertFalse(inDeviceScope(listOf("a", "router"), second))
        assertTrue(inDeviceScope(emptyList(), second))
    }

    @Test fun `selected refs deduplicate active shared ports and exclude down and unselected rows`() {
        val up = Tunnel("router", 22, "g", 40022, VisitorState(TunnelStatus.DIRECT))
        val connections = listOf(Connection("1", "router", 22, PortKind.SSH), Connection("2", "router", 22, PortKind.SSH), Connection("3", "router", 80, PortKind.WEB))
        val rows = connectionRows("a", connections, TunnelsState(tunnels = listOf(up))) + connectionRows("b", connections, TunnelsState(tunnels = listOf(up)))
        assertEquals(setOf(TunnelRef("a", "router", 22)), selectedTunnelRefs(rows, setOf("a/1", "a/2", "a/3")))
    }
}
