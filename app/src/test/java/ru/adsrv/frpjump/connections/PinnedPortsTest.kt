package ru.adsrv.frpjump.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.api.WireJson
import ru.adsrv.frpjump.ports.PortKind

class PinnedPortsTest {
    private val pins = listOf(
        PortPin("p1", "c1", "llm", 8080, 18080),
        PortPin("p2", "c9", "wb", 22, 18022),
    )
    private val held = listOf(HeldPort("p1", "wb", 80, 40000))

    private fun problem(text: String, device: String = "mqtt", port: Int? = 1883, editing: String? = null, profile: String = "p1") =
        localPortProblem(text, profile, device, port, editing, pins, held)?.reason

    @Test
    fun `blank is Auto and fine`() {
        assertNull(problem(""))
        assertNull(parseLocalPort("  "))
    }

    @Test
    fun `out of range or not a number`() {
        assertEquals(LocalPortProblem.Reason.OUT_OF_RANGE, problem("1023"))
        assertEquals(LocalPortProblem.Reason.OUT_OF_RANGE, problem("70000"))
        assertEquals(LocalPortProblem.Reason.OUT_OF_RANGE, problem("80a"))
        assertNull(problem("1024"))
        assertNull(problem("65535"))
    }

    @Test
    fun `two connections cannot pin the same port, across devices and profiles`() {
        assertEquals(LocalPortProblem.Reason.PINNED_ELSEWHERE, problem("18080"))
        assertEquals(LocalPortProblem.Reason.PINNED_ELSEWHERE, problem("18022", device = "llm", port = 8080))
        val p = localPortProblem("18080", "p1", "wb", 80, null, pins, held)
        assertEquals("llm:8080", p?.other)
    }

    @Test
    fun `the connection keeps its own pin when changed`() {
        assertNull(problem("18080", device = "llm", port = 8080, editing = "c1"))
    }

    @Test
    fun `connections sharing a tunnel share its pin or stay Auto`() {
        // Another connection to llm:8080 (say SSH as another user) may name the same pin...
        assertNull(problem("18080", device = "llm", port = 8080))
        // ...but not another one.
        assertEquals(LocalPortProblem.Reason.OTHER_PIN_ON_TUNNEL, problem("19000", device = "llm", port = 8080))
        assertNull(problem("", device = "llm", port = 8080))
    }

    @Test
    fun `a port another tunnel listens on now is refused, the own tunnel's is not`() {
        assertEquals(LocalPortProblem.Reason.IN_USE, problem("40000", device = "mqtt", port = 1883))
        assertNull(problem("40000", device = "wb", port = 80))
    }

    @Test
    fun `a new pin for a tunnel up on another port is refused, it would move under its sessions`() {
        val p = localPortProblem("41000", "p1", "wb", 80, null, pins, held)
        assertEquals(LocalPortProblem.Reason.TUNNEL_UP_OTHER_PORT, p?.reason)
        assertEquals("40000", p?.other)
        // Auto there is fine, and so is pinning the port it is on now.
        assertNull(problem("", device = "wb", port = 80))
    }

    @Test
    fun `the save checks again, against a pin another profile took meanwhile`() {
        val mine = Connection("new", "llm2", 9000, PortKind.TCP, localPort = 18022)
        assertEquals(LocalPortProblem.Reason.PINNED_ELSEWHERE, pinProblemOnSave("p1", mine, pins, held)?.reason)
        assertNull(pinProblemOnSave("p1", mine.copy(localPort = 18100), pins, held))
        assertNull(pinProblemOnSave("p1", mine.copy(localPort = null), pins, held))
    }

    @Test
    fun `a tunnel's pin comes from its connections`() {
        val list = listOf(
            Connection("a", "llm", 8080, PortKind.TCP, localPort = 18080),
            Connection("b", "llm", 8080, PortKind.TCP),
            Connection("c", "wb", 80, PortKind.WEB),
        )
        assertEquals(mapOf("llm:8080" to 18080), tunnelPins(list))
        assertEquals(listOf(PortPin("p1", "a", "llm", 8080, 18080)), portPins("p1", list))
    }

    @Test
    fun `the pin is stored on the connection and read back, absent as Auto`() {
        val pinned = Connection("a", "llm", 8080, PortKind.TCP, localPort = 18080)
        val text = WireJson.encodeToString(Connection.serializer(), pinned)
        assertEquals(pinned, WireJson.decodeFromString(Connection.serializer(), text))
        assertNull(WireJson.decodeFromString(Connection.serializer(), """{"id":"b","device":"wb","port":80,"kind":"web"}""").localPort)
    }

    @Test
    fun `the sheet's draft carries the pin both ways`() {
        val c = Connection("a", "llm", 8080, PortKind.TCP, localPort = 18080)
        val draft = draftOf(c)
        assertEquals("18080", draft.localPortText)
        assertEquals(18080, connectionOf(draft, "a", "llm").localPort)
        assertNull(connectionOf(draft.copy(localPortText = ""), "a", "llm").localPort)
    }

    @Test
    fun `a pin given up is released unless another connection to the tunnel keeps it`() {
        val pinned = Connection("a", "llm", 8080, PortKind.TCP, localPort = 18080)
        // Deleted, or back to Auto.
        assertEquals("llm:8080" to 18080, pinToRelease(pinned, emptyList()))
        assertEquals("llm:8080" to 18080, pinToRelease(pinned, listOf(pinned.copy(localPort = null))))
        // Moved to another pin: the old one goes.
        assertEquals("llm:8080" to 18080, pinToRelease(pinned, listOf(pinned.copy(localPort = 19000))))
        // Another connection to llm:8080 still pins it.
        assertNull(pinToRelease(pinned, listOf(Connection("b", "llm", 8080, PortKind.TCP, localPort = 18080))))
        // Nothing pinned before.
        assertNull(pinToRelease(pinned.copy(localPort = null), emptyList()))
        assertNull(pinToRelease(null, emptyList()))
    }
}
