package ru.adsrv.frpjump.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ports.PortKind

class ConnectionTest {
    private val fallback = { kind: PortKind, port: Int -> "${kind.name} $port" }

    private fun conn(id: String, device: String, port: Int, name: String, kind: PortKind = PortKind.WEB) =
        Connection(id, device, port, kind, name)

    @Test
    fun `a port is 1 to 65535, digits only`() {
        assertEquals(1, parsePort("1"))
        assertEquals(65535, parsePort("65535"))
        assertEquals(80, parsePort(" 80 "))
        assertEquals(80, parsePort("0080"))
        for (bad in listOf("", " ", "0", "65536", "99999", "123456", "-1", "8o", "1.5", "+80")) assertNull(bad, parsePort(bad))
    }

    @Test
    fun `a missing port and one out of range are told apart`() {
        assertEquals(PortProblem.MISSING, portProblem(""))
        assertEquals(PortProblem.MISSING, portProblem("  "))
        assertEquals(PortProblem.OUT_OF_RANGE, portProblem("0"))
        assertEquals(PortProblem.OUT_OF_RANGE, portProblem("70000"))
        assertNull(portProblem("8080"))
    }

    @Test
    fun `presets are the contract's`() {
        assertEquals(listOf(80, 443, 8080, 1880, 3000), PORT_PRESETS.getValue(PortKind.WEB).map { it.port })
        assertEquals(listOf(22, 2222), PORT_PRESETS.getValue(PortKind.SSH).map { it.port })
        assertEquals(listOf(1883, 502, 5432), PORT_PRESETS.getValue(PortKind.TCP).map { it.port })
    }

    @Test
    fun `a blank name becomes the preset's, the SSH user or the fallback`() {
        assertEquals("homeui", resolvedName(ConnectionDraft(PortKind.WEB, "80", " "), fallback))
        assertEquals("MQTT", resolvedName(ConnectionDraft(PortKind.TCP, "1883", ""), fallback))
        assertEquals("admin", resolvedName(ConnectionDraft(PortKind.SSH, "22", "", user = "admin"), fallback))
        assertEquals("WEB 8080", resolvedName(ConnectionDraft(PortKind.WEB, "8080", ""), fallback))
        // A preset port of another kind does not lend its name.
        assertEquals("TCP 80", resolvedName(ConnectionDraft(PortKind.TCP, "80", ""), fallback))
        assertEquals("My UI", resolvedName(ConnectionDraft(PortKind.WEB, "80", "  My UI "), fallback))
    }

    @Test
    fun `several connections to one port need different names`() {
        val existing = listOf(conn("a", "wb", 22, "root", PortKind.SSH))
        val draft = ConnectionDraft(PortKind.SSH, "22", "", user = "root")
        assertTrue(draftProblems(draft, "ROOT", "wb", existing, editingId = null).nameTaken)
        assertFalse(draftProblems(draft, "admin", "wb", existing, editingId = null).nameTaken)
        // The same name on another port or device is fine; so is a connection keeping its own name.
        assertFalse(draftProblems(draft.copy(portText = "2222"), "root", "wb", existing, null).nameTaken)
        assertFalse(draftProblems(draft, "root", "other", existing, null).nameTaken)
        assertFalse(draftProblems(draft, "root", "wb", existing, editingId = "a").nameTaken)
    }

    @Test
    fun `SSH needs a user, other kinds do not`() {
        assertTrue(draftProblems(ConnectionDraft(PortKind.SSH, "22", "x", user = " "), "x", "wb", emptyList(), null).userMissing)
        assertTrue(draftProblems(ConnectionDraft(PortKind.WEB, "80", "x", user = ""), "x", "wb", emptyList(), null).ok)
        assertFalse(draftProblems(ConnectionDraft(PortKind.WEB, "0", "x"), "x", "wb", emptyList(), null).ok)
    }

    @Test
    fun `HTTPS is kept for web only, the user for SSH only`() {
        val web = connectionOf(ConnectionDraft(PortKind.WEB, "443", "", https = true, user = "root"), "1", "wb", "ui")
        assertEquals(Connection("1", "wb", 443, PortKind.WEB, "ui", https = true, user = null), web)
        val tcp = connectionOf(ConnectionDraft(PortKind.TCP, "1883", "", https = true), "2", "wb", "MQTT")
        assertFalse(tcp.https)
        val ssh = connectionOf(ConnectionDraft(PortKind.SSH, "22", "", user = " admin "), "3", "wb", "admin")
        assertEquals("admin", ssh.user)
        assertEquals(ConnectionDraft(PortKind.SSH, "22", "admin", https = false, user = "admin"), draftOf(ssh))
    }

    @Test
    fun `an SSH connection without a user logs in as root`() {
        assertEquals("root", Connection("1", "wb", 22, PortKind.SSH, "x").sshUser)
        assertEquals("root", Connection("1", "wb", 22, PortKind.SSH, "x", user = " ").sshUser)
        assertEquals("pi", Connection("1", "wb", 22, PortKind.SSH, "x", user = "pi").sshUser)
    }

    @Test
    fun `SSH and other kinds cannot share a port, web and TCP can`() {
        val ssh = conn("a", "wb", 8022, "console", PortKind.SSH)
        val tcp = ConnectionDraft(PortKind.TCP, "8022", "raw")
        assertEquals("ssh", draftProblems(tcp, "raw", "wb", listOf(ssh), null).protocolClash)
        assertFalse(draftProblems(tcp, "raw", "wb", listOf(ssh), null).ok)
        // The connection being changed does not clash with itself, nor ports elsewhere.
        assertNull(draftProblems(tcp, "raw", "wb", listOf(ssh), editingId = "a").protocolClash)
        assertNull(draftProblems(tcp, "raw", "pc", listOf(ssh), null).protocolClash)
        val web = conn("b", "wb", 80, "homeui")
        assertNull(draftProblems(ConnectionDraft(PortKind.TCP, "80", "raw"), "raw", "wb", listOf(web), null).protocolClash)
        assertEquals("tcp", draftProblems(ConnectionDraft(PortKind.SSH, "80", "x", user = "root"), "x", "wb", listOf(web), null).protocolClash)
        // 22 and 2222 are SSH to the server whatever the kind: no clash there.
        val root = conn("c", "wb", 22, "root", PortKind.SSH)
        assertNull(draftProblems(ConnectionDraft(PortKind.TCP, "22", "raw"), "raw", "wb", listOf(root), null).protocolClash)
    }
}
