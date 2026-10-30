package ru.adsrv.frpjump.connections

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.ssh.MemoryStorage

class ConnectionStoreTest {
    private val file = MemoryStorage()

    @Test
    fun `connections are added, changed in place and removed, and survive a restart`() {
        val store = ConnectionStore(file)
        val web = Connection("1", "wb", 80, PortKind.WEB, "homeui")
        val ssh = Connection("2", "wb", 22, PortKind.SSH, "root", user = "root")
        store.save(web)
        store.save(ssh)
        store.save(web.copy(name = "UI", https = true))
        assertEquals(listOf(web.copy(name = "UI", https = true), ssh), store.connections.value)
        assertEquals(store.connections.value, ConnectionStore(file).connections.value)
        store.remove("1")
        store.remove("missing")
        assertEquals(listOf(ssh), ConnectionStore(file).connections.value)
    }

    @Test
    fun `an unreadable file reads as no connections`() {
        file.text = "{not json"
        assertEquals(emptyList<Connection>(), ConnectionStore(file).connections.value)
    }
}
