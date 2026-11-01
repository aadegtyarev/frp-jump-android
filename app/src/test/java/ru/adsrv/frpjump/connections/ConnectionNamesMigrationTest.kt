package ru.adsrv.frpjump.connections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.MemoryStorage

/** Names the app made up up to 0.2.0-beta1 become default names; the owner's own stay. */
class ConnectionNamesMigrationTest {
    private fun record(name: String, port: Int, kind: String, user: String? = null) =
        """{"id":"$name-$port","device":"wb","port":$port,"kind":"$kind","name":"$name"${user?.let { ""","user":"$it"""" } ?: ""}}"""

    private fun customName(name: String, port: Int, kind: String, user: String? = null): String? =
        readConnections("[${record(name, port, kind, user)}]").single().customName

    @Test
    fun `names made up from the kind and port become the default name`() {
        // "Web 8080" / "TCP 1234" / "SSH 2222": the kind and the port in English.
        assertNull(customName("Web 8080", 8080, "web"))
        assertNull(customName("TCP 1234", 1234, "tcp"))
        assertNull(customName("SSH 2222", 2222, "ssh"))
        // From the upgrade of 0.1: the two presets and ports without a label.
        assertNull(customName("Web", 80, "web"))
        assertNull(customName("SSH", 22, "ssh", user = "root"))
        assertNull(customName("Port 5900", 5900, "tcp"))
        assertNull(customName("", 5900, "tcp"))
    }

    @Test
    fun `the owner's names, presets' and SSH users' stay as they are`() {
        assertEquals("Router", customName("Router", 80, "web"))
        assertEquals("Роутер", customName("Роутер", 80, "web"))
        // Not renamed to "Web" or "SSH": they say the same in any language.
        assertEquals("homeui", customName("homeui", 80, "web"))
        assertEquals("MQTT", customName("MQTT", 1883, "tcp"))
        assertEquals("admin", customName("admin", 22, "ssh", user = "admin"))
        // Only an exact match counts: another port or kind is the owner's choice.
        assertEquals("Web 8081", customName("Web 8081", 8080, "web"))
        assertEquals("TCP 8080", customName("TCP 8080", 8080, "web"))
        assertEquals("Web", customName("Web", 8080, "web"))
        assertEquals("SSH", customName("SSH", 2222, "ssh"))
        assertEquals("web 8080", customName("web 8080", 8080, "web"))
    }

    @Test
    fun `a made-up name whose default another connection on the port shows is kept`() {
        val list = readConnections(
            "[" + listOf(
                record("Web", 80, "web"),
                record("homeui", 80, "web"),
                record("SSH", 22, "ssh", user = "root"),
                record("root", 22, "ssh", user = "root"),
                // Another row on 8080 already reads "Web 8080": "Port 8080" stays as it was.
                record("Port 8080", 8080, "web"),
                record("Web 8080", 8080, "web"),
            ).joinToString(",") + "]",
        )
        assertEquals(listOf(null, "homeui", null, "root", "Port 8080", null), list.map { it.customName })
    }

    @Test
    fun `records written since are read as they are`() {
        val list = readConnections(
            """[{"id":"1","device":"wb","port":8080,"kind":"web"},{"id":"2","device":"wb","port":80,"kind":"web","custom_name":"Web"}]""",
        )
        assertNull(list[0].customName)
        // "Web" here is the owner's: it was saved as a name of its own.
        assertEquals("Web", list[1].customName)
    }

    @Test
    fun `the store reads old files and writes the new field only`() {
        val file = MemoryStorage("[${record("Web 8080", 8080, "web")},${record("Router", 80, "web")}]")
        val store = ConnectionStore(file, MemoryStorage())
        assertEquals(listOf(null, "Router"), store.connections.value.map { it.customName })
        store.save(store.connections.value[0].copy(https = true))
        val text = file.text.orEmpty()
        assertFalse(text, "\"name\"" in text)
        assertTrue(text, "\"custom_name\":\"Router\"" in text)
        assertEquals(listOf(null, "Router"), ConnectionStore(file, MemoryStorage()).connections.value.map { it.customName })
    }
}
