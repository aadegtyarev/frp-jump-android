package ru.adsrv.frpjump.connections

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import ru.adsrv.frpjump.ports.LocalPortStore
import ru.adsrv.frpjump.ports.PortKind
import ru.adsrv.frpjump.profiles.FakeKeystore
import ru.adsrv.frpjump.profiles.FileStorages
import ru.adsrv.frpjump.profiles.ProfileStore
import ru.adsrv.frpjump.profiles.scratchDir
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.ssh.SshLoginMemory
import java.io.File
import java.io.IOException

class ConnectionMigrationTest {
    private val dir = scratchDir()
    private val files = FileStorages()
    private var ids = 0

    private fun storage(name: String) = files.storage(File(dir, name))
    private val connections get() = storage("connections.json")
    private val legacy get() = storage("device_ports.json")

    private val v1Ports = """{"wb":[{"port":8080,"label":"Node-RED","kind":"web"},{"port":2222,"label":"","kind":"ssh"},{"port":1883,"label":"MQTT","kind":"tcp"}],"pc":[{"port":5900,"label":"VNC","kind":"tcp"}]}"""

    /** The shared map: wb's presets were used, ikar's web only; another profile's keys must not count. */
    private val localPorts = mapOf(
        "p1/wb:80" to 40002,
        "p1/wb:22" to 40001,
        "p1/ikar:80" to 40003,
        "p1/wb:8080" to 40004,
        "p2/beelink:22" to 40005,
    )

    private val users = mapOf("wb:22" to "admin")

    private fun migrate() = migrateDevicePorts(
        profile = "p1",
        connections = connections,
        legacy = legacy,
        localPorts = localPorts,
        sshUser = { device, port -> users["$device:$port"] ?: "root" },
        newId = { "c${++ids}" },
    )

    private fun expected(): List<Connection> = listOf(
        Connection("c1", "ikar", 80, PortKind.WEB, "Web"),
        Connection("c2", "pc", 5900, PortKind.TCP, "VNC"),
        Connection("c3", "wb", 80, PortKind.WEB, "Web"),
        Connection("c4", "wb", 22, PortKind.SSH, "SSH", user = "admin"),
        Connection("c5", "wb", 1883, PortKind.TCP, "MQTT"),
        Connection("c6", "wb", 2222, PortKind.SSH, "Port 2222", user = "root"),
        Connection("c7", "wb", 8080, PortKind.WEB, "Node-RED"),
    )

    private fun crashOnce(block: () -> Unit) {
        try {
            block()
            fail("the crash was not simulated")
        } catch (_: IOException) {
        }
    }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    @Test
    fun `a new profile gets an empty list, once`() {
        assertTrue(migrateDevicePorts("p9", connections, legacy, localPorts, { _, _ -> "root" }, { "x" }))
        assertEquals("[]", connections.read())
        assertFalse(migrateDevicePorts("p9", connections, legacy, localPorts, { _, _ -> "root" }, { "x" }))
    }

    @Test
    fun `own ports and the presets that were used become named connections`() {
        legacy.write(v1Ports)
        assertTrue(migrate())
        assertEquals(expected(), ConnectionStore(connections, MemoryStorage()).connections.value)
        assertNull(legacy.read())
    }

    @Test
    fun `running it again changes nothing`() {
        legacy.write(v1Ports)
        migrate()
        val written = connections.read()
        assertFalse(migrate())
        assertEquals(written, connections.read())
        assertEquals(7, ids)
    }

    @Test
    fun `presets alone, without a ports file, still migrate`() {
        assertTrue(migrate())
        assertEquals(
            listOf("ikar:80:Web", "wb:80:Web", "wb:22:SSH"),
            ConnectionStore(connections, MemoryStorage()).connections.value.map { "${it.device}:${it.port}:${it.name}" },
        )
    }

    @Test
    fun `a crash before the list is written leaves the old ports for the next start`() {
        legacy.write(v1Ports)
        files.crashOn = "connections.json"
        crashOnce { migrate() }
        assertNull(connections.read())
        assertEquals(v1Ports, legacy.read())
        ids = 0
        assertTrue(migrate())
        assertEquals(expected(), ConnectionStore(connections, MemoryStorage()).connections.value)
    }

    @Test
    fun `a crash after the list is written only leaves the old file to delete`() {
        legacy.write(v1Ports)
        files.crashOnDelete = "device_ports.json"
        crashOnce { migrate() }
        assertEquals(expected(), ConnectionStore(connections, MemoryStorage()).connections.value)
        assertFalse(migrate())
        assertNull(legacy.read())
        assertEquals(expected(), ConnectionStore(connections, MemoryStorage()).connections.value)
    }

    @Test
    fun `an unreadable ports file stays in place and nothing is written`() {
        legacy.write("{broken")
        assertFalse(migrate())
        assertNull(connections.read())
        assertEquals("{broken", legacy.read())
        // Even once a list exists (the owner saved a connection), it is never deleted.
        connections.write("[]")
        assertFalse(migrate())
        assertEquals("{broken", legacy.read())
    }

    @Test
    fun `the owner's own ports on 80 and 22 keep their label and kind, used or not`() {
        legacy.write("""{"wb":[{"port":22,"label":"Console","kind":"ssh"}],"pc":[{"port":80,"label":"Router","kind":"tcp"}]}""")
        migrate()
        assertEquals(
            listOf("ikar:80:Web", "pc:80:Router", "wb:80:Web", "wb:22:Console"),
            ConnectionStore(connections, MemoryStorage()).connections.value.map { "${it.device}:${it.port}:${it.name}" },
        )
        assertEquals(PortKind.TCP, ConnectionStore(connections, MemoryStorage()).connections.value[1].kind)
    }

    @Test
    fun `v1 data upgraded to a profile becomes connections, with local ports and logins kept`() {
        val filesDir = File(dir, "files").apply { mkdirs() }
        File(filesDir, "device_ports.json").writeText("""{"wb":[{"port":8080,"label":"Node-RED","kind":"web"}]}""")
        File(filesDir, "local_ports.json").writeText("""{"wb:80":40002,"wb:22":40001}""")
        File(filesDir, "ssh_logins.json").writeText("""{"wb":{"22":{"user":"admin"}}}""")
        File(filesDir, "enrollment.json").writeText("{}")
        val store = ProfileStore(filesDir, FakeKeystore()::box, files::storage, newId = { "id1" })
        store.load()
        val profileDir = store.dir("id1")
        val ports = LocalPortStore(files.storage(File(filesDir, "local_ports.json")))
        val logins = SshLoginMemory(files.storage(File(profileDir, "ssh_logins.json")))
        migrateDevicePorts(
            "id1",
            files.storage(File(profileDir, "connections.json")),
            files.storage(File(profileDir, "device_ports.json")),
            ports.load(),
            { d, p -> logins.get(d, p).user },
            { "c${++ids}" },
        )
        assertEquals(
            listOf(
                Connection("c1", "wb", 80, PortKind.WEB, "Web"),
                Connection("c2", "wb", 22, PortKind.SSH, "SSH", user = "admin"),
                Connection("c3", "wb", 8080, PortKind.WEB, "Node-RED"),
            ),
            ConnectionStore(files.storage(File(profileDir, "connections.json")), MemoryStorage()).connections.value,
        )
        assertFalse(File(profileDir, "device_ports.json").exists())
        // Tunnels stay keyed by (profile, device, port): the same local ports.
        assertEquals(mapOf("id1/wb:80" to 40002, "id1/wb:22" to 40001), ports.load())
        assertEquals("admin", logins.get("wb", 22).user)
    }

    @Test
    fun `migrated SSH users come from the login memory`() {
        val memory = SshLoginMemory(MemoryStorage("""{"wb":{"22":{"user":"pi","method":"password"}}}"""))
        migrateDevicePorts("p1", connections, legacy, mapOf("p1/wb:22" to 40001), { d, p -> memory.get(d, p).user }, { "c" })
        assertEquals("pi", ConnectionStore(connections, MemoryStorage()).connections.value.single().user)
    }
}
