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
        val web = Connection("1", "wb", 80, PortKind.WEB, "homeui")
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
}
