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

    @Test
    fun `a favourite is stored with its connection, survives a restart and is gone with it`() {
        val store = ConnectionStore(file, copy)
        val web = Connection("1", "wb", 8080, PortKind.WEB, "homeui")
        val ssh = Connection("2", "wb", 22, PortKind.SSH, "root", user = "root")
        store.save(web)
        store.save(ssh)
        store.setFavorite("2", true)
        assertEquals(listOf(web, ssh.copy(favorite = true)), store.connections.value)
        assertEquals(store.connections.value, ConnectionStore(file, copy).connections.value)
        store.setFavorite("2", false)
        assertEquals(listOf(web, ssh), ConnectionStore(file, copy).connections.value)
        store.setFavorite("2", true)
        store.remove("2")
        assertEquals(listOf(web), ConnectionStore(file, copy).connections.value)
        // Another connection saved under that id later is not a favourite.
        store.save(ssh)
        assertEquals(false, ConnectionStore(file, copy).connections.value.last().favorite)
    }

    @Test
    fun `a missing connection or an unchanged flag writes nothing`() {
        val store = ConnectionStore(file, copy)
        store.save(Connection("1", "wb", 22, PortKind.SSH, "root"))
        val before = file.text
        store.setFavorite("missing", true)
        store.setFavorite("1", false)
        assertEquals(before, file.text)
    }

    @Test
    fun `a file written before favourites reads as no favourite, and a connection without one is written as before`() {
        file.text = """[{"id":"1","device":"wb","port":22,"kind":"ssh","custom_name":"root","user":"root"}]"""
        val store = ConnectionStore(file, copy)
        assertEquals(false, store.connections.value.single().favorite)
        // Saved again, the file has no new key until something is a favourite: older versions read it as they always did.
        store.save(store.connections.value.single().copy(customName = "shell"))
        org.junit.Assert.assertFalse(file.text!!.contains("favorite"))
        store.setFavorite("1", true)
        org.junit.Assert.assertTrue(file.text!!.contains("\"favorite\":true"))
    }

    @Test
    fun `an edit keeps the favourite flag, and only setFavorite changes it`() {
        val store = ConnectionStore(file, copy)
        store.save(Connection("1", "wb", 22, PortKind.SSH, "root", user = "root"))
        store.setFavorite("1", true)
        // The sheet builds the edited connection from the draft: no flag in it.
        store.save(Connection("1", "wb", 22, PortKind.SSH, "renamed", user = "root"))
        assertEquals(true, ConnectionStore(file, copy).connections.value.single().favorite)
        assertEquals("renamed", store.connections.value.single().customName)
        store.save(store.connections.value.single().copy(favorite = false))
        assertEquals(true, store.connections.value.single().favorite)
    }

    @Test
    fun `the flag never changes what the assistant's permissions are bound to`() {
        val c = Connection("1", "wb", 22, PortKind.SSH, "root", user = "root")
        assertEquals(c, c.copy(favorite = true).withoutFavorite)
        assertEquals(c, c.withoutFavorite)
        val fp = { x: Connection -> ru.adsrv.frpjump.settings.GrantFingerprint.of(x, "p", "alias", false, null, null, null, null) }
        assertEquals(fp(c), fp(c.copy(favorite = true)))
    }
}
