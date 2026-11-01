package ru.adsrv.frpjump.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ports.PortKind

class ConnectionTest {
    private val fallback = { kind: PortKind -> kind.name }

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
        assertEquals(listOf(502, 5432), PORT_PRESETS.getValue(PortKind.TCP).map { it.port })
        // 1883 moved to MQTT; no preset name: the default one says "MQTT".
        assertEquals(listOf(PortPreset(1883)), PORT_PRESETS.getValue(PortKind.MQTT))
        PortKind.entries.forEach { assertTrue(it.name, PORT_PRESETS.getValue(it).isNotEmpty()) }
    }

    @Test
    fun `an MQTT connection is called MQTT on 1883 and goes to the server as tcp`() {
        assertEquals("MQTT", draftLabel(ConnectionDraft(PortKind.MQTT, "1883", ""), fallback))
        assertEquals("MQTT 1884", draftLabel(ConnectionDraft(PortKind.MQTT, "1884", ""), fallback))
        assertEquals("tcp", ru.adsrv.frpjump.ports.wireProtocolFor(PortKind.MQTT, 1883))
        // As every kind on an SSH port, so both clients name the port the same way.
        assertEquals("ssh", ru.adsrv.frpjump.ports.wireProtocolFor(PortKind.MQTT, 22))
        assertEquals("\"mqtt\"", ru.adsrv.frpjump.api.WireJson.encodeToString(PortKind.serializer(), PortKind.MQTT))
    }

    @Test
    fun `Open as MQTT keeps the connection and its wire protocol, changing only the kind`() {
        val tcp = Connection("id-1", "wb", 1883, PortKind.TCP, "broker", localPort = 21883)
        val mqtt = tcp.asMqtt()
        assertEquals(tcp.copy(kind = PortKind.MQTT), mqtt)
        assertEquals(ru.adsrv.frpjump.ports.wireProtocolFor(tcp.kind, tcp.port), ru.adsrv.frpjump.ports.wireProtocolFor(mqtt.kind, mqtt.port))
        // An unnamed one now shows the MQTT default name.
        assertEquals("MQTT", Connection("id-2", "wb", 1883, PortKind.TCP, null).asMqtt().label(fallback))
        // Another connection on that port saved as TCP can still share the tunnel: same wire protocol.
        val draft = draftOf(mqtt)
        assertNull(draftProblems(draft, "wb", listOf(tcp.copy(id = "other", customName = "raw")), mqtt.id, fallback).protocolClash)
    }

    @Test
    fun `a blank name is saved as none and shows the kind, and the port unless it is the kind's own`() {
        assertNull(customNameOf(ConnectionDraft(PortKind.WEB, "80", " ")))
        assertEquals("My UI", customNameOf(ConnectionDraft(PortKind.WEB, "80", "  My UI ")))
        assertEquals("WEB", draftLabel(ConnectionDraft(PortKind.WEB, "80", " "), fallback))
        assertEquals("WEB HTTPS", draftLabel(ConnectionDraft(PortKind.WEB, "443", ""), fallback))
        assertEquals("WEB 8080", draftLabel(ConnectionDraft(PortKind.WEB, "8080", ""), fallback))
        assertEquals("SSH", draftLabel(ConnectionDraft(PortKind.SSH, "22", "", user = "admin"), fallback))
        assertEquals("SSH 2222", draftLabel(ConnectionDraft(PortKind.SSH, "2222", ""), fallback))
        // TCP has no port of its own; a web port under another kind is just a port.
        assertEquals("TCP 1883", draftLabel(ConnectionDraft(PortKind.TCP, "1883", ""), fallback))
        assertEquals("TCP 80", draftLabel(ConnectionDraft(PortKind.TCP, "80", ""), fallback))
        assertEquals("My UI", draftLabel(ConnectionDraft(PortKind.WEB, "80", "  My UI "), fallback))
    }

    @Test
    fun `a connection without a name of its own is called by the default in the words given`() {
        val russian = { kind: PortKind -> if (kind == PortKind.WEB) "Веб" else kind.name }
        assertEquals("Веб 8080", Connection("1", "wb", 8080, PortKind.WEB, null).label(russian))
        assertEquals("Веб", Connection("1", "wb", 80, PortKind.WEB, null).label(russian))
        assertEquals("SSH", Connection("1", "wb", 22, PortKind.SSH, null, user = "pi").label(russian))
        assertEquals("Мой", Connection("1", "wb", 8080, PortKind.WEB, "Мой").label(russian))
    }

    @Test
    fun `a preset chip fills in its name, unless the owner typed one`() {
        val nodeRed = PortPreset(1880, "Node-RED")
        val grafana = PortPreset(3000, "Grafana")
        assertEquals("Node-RED", nameAfterPreset("", nodeRed))
        assertEquals("Grafana", nameAfterPreset("Node-RED", grafana))
        // A web port preset without a name leaves the default ("Web") to the kind.
        assertEquals("", nameAfterPreset("", PortPreset(80)))
        assertEquals("", nameAfterPreset("Grafana", PortPreset(8080)))
        assertEquals("My UI", nameAfterPreset("My UI", grafana))
        assertTrue(isPresetName(" Modbus TCP "))
        assertFalse(isPresetName("root"))
    }

    @Test
    fun `several connections to one port need different names`() {
        val existing = listOf(conn("a", "wb", 22, "root", PortKind.SSH))
        val draft = ConnectionDraft(PortKind.SSH, "22", "root", user = "root")
        assertTrue(draftProblems(draft.copy(name = "ROOT"), "wb", existing, editingId = null, fallback).nameTaken)
        assertFalse(draftProblems(draft.copy(name = "admin"), "wb", existing, editingId = null, fallback).nameTaken)
        // Unnamed, it would be called "SSH": free here.
        assertFalse(draftProblems(draft.copy(name = ""), "wb", existing, editingId = null, fallback).nameTaken)
        // The same name on another port or device is fine; so is a connection keeping its own name.
        assertFalse(draftProblems(draft.copy(portText = "2222"), "wb", existing, null, fallback).nameTaken)
        assertFalse(draftProblems(draft, "other", existing, null, fallback).nameTaken)
        assertFalse(draftProblems(draft, "wb", existing, editingId = "a", fallback).nameTaken)
        // Names are compared as shown: an unnamed connection by its default name.
        val unnamed = listOf(Connection("b", "wb", 8080, PortKind.WEB, null))
        assertTrue(draftProblems(ConnectionDraft(PortKind.WEB, "8080", "web 8080"), "wb", unnamed, null, fallback).nameTaken)
        assertTrue(draftProblems(ConnectionDraft(PortKind.WEB, "8080", ""), "wb", unnamed, null, fallback).nameTaken)
    }

    @Test
    fun `SSH needs a user, other kinds do not`() {
        assertTrue(draftProblems(ConnectionDraft(PortKind.SSH, "22", "x", user = " "), "wb", emptyList(), null, fallback).userMissing)
        assertTrue(draftProblems(ConnectionDraft(PortKind.WEB, "80", "x", user = ""), "wb", emptyList(), null, fallback).ok)
        assertFalse(draftProblems(ConnectionDraft(PortKind.WEB, "0", "x"), "wb", emptyList(), null, fallback).ok)
    }

    @Test
    fun `HTTPS is kept for web only, the user for SSH only`() {
        val web = connectionOf(ConnectionDraft(PortKind.WEB, "443", "ui", https = true, user = "root"), "1", "wb")
        assertEquals(Connection("1", "wb", 443, PortKind.WEB, "ui", https = true, user = null), web)
        val tcp = connectionOf(ConnectionDraft(PortKind.TCP, "1883", "", https = true), "2", "wb")
        assertFalse(tcp.https)
        assertNull(tcp.customName)
        val ssh = connectionOf(ConnectionDraft(PortKind.SSH, "22", "", user = " admin "), "3", "wb")
        assertEquals("admin", ssh.user)
        // Unnamed stays unnamed when changed.
        assertEquals(ConnectionDraft(PortKind.SSH, "22", "", https = false, user = "admin"), draftOf(ssh))
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
        assertEquals("ssh", draftProblems(tcp, "wb", listOf(ssh), null, fallback).protocolClash)
        assertFalse(draftProblems(tcp, "wb", listOf(ssh), null, fallback).ok)
        // The connection being changed does not clash with itself, nor ports elsewhere.
        assertNull(draftProblems(tcp, "wb", listOf(ssh), editingId = "a", fallback).protocolClash)
        assertNull(draftProblems(tcp, "pc", listOf(ssh), null, fallback).protocolClash)
        val web = conn("b", "wb", 80, "homeui")
        assertNull(draftProblems(ConnectionDraft(PortKind.TCP, "80", "raw"), "wb", listOf(web), null, fallback).protocolClash)
        assertEquals("tcp", draftProblems(ConnectionDraft(PortKind.SSH, "80", "x", user = "root"), "wb", listOf(web), null, fallback).protocolClash)
        // 22 and 2222 are SSH to the server whatever the kind: no clash there.
        val root = conn("c", "wb", 22, "root", PortKind.SSH)
        assertNull(draftProblems(ConnectionDraft(PortKind.TCP, "22", "raw"), "wb", listOf(root), null, fallback).protocolClash)
    }

    @Test
    fun `80 is http and 443 https whatever the flag, other ports as set`() {
        assertEquals(false, schemeIsHttps(80, true))
        assertEquals(true, schemeIsHttps(443, false))
        assertEquals(true, schemeIsHttps(8443, true))
        assertEquals(false, schemeIsHttps(8080, false))
        assertEquals(false, asksForHttps(80))
        assertEquals(false, asksForHttps(443))
        assertEquals(true, asksForHttps(8080))
        assertEquals(true, asksForHttps(null))
        val web80 = connectionOf(ConnectionDraft(PortKind.WEB, "80", "", https = true), "3", "wb")
        assertEquals(false, web80.https)
    }
}
