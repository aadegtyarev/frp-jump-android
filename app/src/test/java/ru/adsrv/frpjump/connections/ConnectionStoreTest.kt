package ru.adsrv.frpjump.connections

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.MemoryStorage

class ConnectionStoreTest {
    private val file = MemoryStorage()
    private val copy = MemoryStorage()

    @Test
    fun `connections are added, changed in place and removed, and survive a restart`() {
        val store = ConnectionStore(file, copy)
        val web = Connection("1", "wb", 8080, PortKind.WEB, "homeui")
        val ssh = Connection("2", "wb", 22, PortKind.SSH, "root", user = "root")
        store.save(web)
        store.save(ssh)
        store.save(web.copy(customName = "UI", https = true))
        assertEquals(listOf(web.copy(customName = "UI", https = true), ssh), store.connections.value)
        assertEquals(store.connections.value, ConnectionStore(file, copy).connections.value)
        store.remove("1")
        store.remove("missing")
        assertEquals(listOf(ssh), ConnectionStore(file, copy).connections.value)
    }

    @Test
    fun `Open as MQTT saves the same connection as MQTT in place, and it reads back as mqtt`() {
        val store = ConnectionStore(file, copy)
        val tcp = Connection("1", "wb", 1883, PortKind.TCP, "broker", localPort = 21883)
        val other = Connection("2", "wb", 22, PortKind.SSH, "root", user = "root")
        store.save(tcp)
        store.save(other)
        store.save(tcp.asMqtt())
        val expected = listOf(tcp.copy(kind = PortKind.MQTT), other)
        assertEquals(expected, store.connections.value)
        assertEquals(expected, ConnectionStore(file, copy).connections.value)
        org.junit.Assert.assertTrue(file.text!!.contains("\"mqtt\""))
    }

    @Test
    fun `an unreadable file is kept as a copy before anything is saved over it`() {
        file.text = "{not json"
        val store = ConnectionStore(file, copy)
        assertEquals(emptyList<Connection>(), store.connections.value)
        assertEquals("{not json", copy.text)
        store.save(Connection("1", "wb", 80, PortKind.WEB, "homeui"))
        assertEquals("{not json", copy.text)
        // A later unreadable file does not replace the first copy.
        file.text = "{again"
        ConnectionStore(file, copy)
        assertEquals("{not json", copy.text)
    }

    @Test
    fun `a stored web connection on 80 or 443 reads with that port's scheme`() {
        ConnectionStore(file, copy).apply {
            save(Connection("1", "wb", 80, PortKind.WEB, null, https = true))
            save(Connection("2", "wb", 443, PortKind.WEB, null, https = false))
            save(Connection("3", "wb", 8443, PortKind.WEB, null, https = true))
        }
        val read = ConnectionStore(file, copy).connections.value.associate { it.id to it.https }
        assertEquals(mapOf("1" to false, "2" to true, "3" to true), read)
    }
}
