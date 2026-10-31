package ru.adsrv.frpjump.devices

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.tunnel.Tunnel
import ru.adsrv.frpjump.tunnel.TunnelStatus
import ru.adsrv.frpjump.tunnel.VisitorState

class DeviceTunnelsTest {
    private fun device(name: String) = DeviceRow(name, online = true, lastSeen = null, disabled = false)
    private fun tunnel(device: String, port: Int) = Tunnel(device, port, "g-$device-$port", 20000 + port, VisitorState(TunnelStatus.RELAYED))

    @Test
    fun `ports are grouped by device, ascending, each once`() {
        val ports = tunnelPorts(listOf(tunnel("b", 80), tunnel("a", 22), tunnel("b", 22), tunnel("b", 80)))
        assertEquals(mapOf("a" to listOf(22), "b" to listOf(22, 80)), ports)
        assertEquals(emptyMap<String, List<Int>>(), tunnelPorts(emptyList()))
    }

    @Test
    fun `devices with tunnels come first, otherwise in the list's order`() {
        val list = listOf(device("a"), device("b"), device("c"), device("d"))
        val ports = mapOf("c" to listOf(22), "b" to listOf(80), "x" to listOf(1))
        assertEquals(listOf("b", "c", "a", "d"), tunnelsFirst(list, ports).map { it.name })
        assertEquals(list, tunnelsFirst(list, emptyMap()))
    }

    @Test
    fun `search keeps the tunnels-first order`() {
        val list = tunnelsFirst(listOf(device("wb-a"), device("router"), device("wb-b")), mapOf("wb-b" to listOf(22)))
        assertEquals(listOf("wb-b", "wb-a"), filterDevices(list, "wb").map { it.name })
    }

    @Test
    fun `many ports are capped with a count of the rest`() {
        assertEquals(listOf(22, 80) to 0, portsShown(listOf(22, 80)))
        assertEquals(listOf(22, 80, 443, 1883) to 2, portsShown(listOf(22, 80, 443, 1883, 8080, 9000)))
    }
}
