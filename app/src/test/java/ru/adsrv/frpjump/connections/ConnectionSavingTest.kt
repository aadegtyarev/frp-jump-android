package ru.adsrv.frpjump.connections

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import ru.adsrv.frpjump.ports.*
import ru.adsrv.frpjump.ssh.MemoryStorage
import ru.adsrv.frpjump.store.TextStorage

class ConnectionSavingTest {
    private class FaultFile : TextStorage {
        var value: String? = null
        var fail = false
        var writes = 0
        override fun read() = value
        override fun write(text: String) {
            writes++
            if (fail) throw IOException("Synthetic store write failure")
            value = text
        }
        override fun delete() { value = null }
    }
    private val connection = Connection("stable-id", "off", 22, PortKind.SSH, localPort = 40000)

    @Test fun `second file failure reports saved intent with same identity not an unsaved connection`() {
        val file = FaultFile()
        val map = FaultFile().apply { fail = true }
        val connections = ConnectionStore(file, MemoryStorage())
        val result = saveConnectionWithReservations(connections, LocalPortStore(map), connection, validate = {}) { ports ->
            reservePins(ports, mapOf(profilePortKey("A", "off", 22) to 40000))
        }
        assertEquals(ConnectionSaveResult.SAVED_PORT_REPAIR_PENDING, result)
        assertEquals(listOf(connection), connections.connections.value)
        assertEquals(listOf(connection), ConnectionStore(file, MemoryStorage()).connections.value)
        assertNull(map.value)
        assertEquals(1, file.writes)
    }

    @Test fun `failure before authoritative write cannot be mistaken for saved`() {
        val file = FaultFile().apply { fail = true }
        val map = FaultFile()
        val connections = ConnectionStore(file, MemoryStorage())
        var reserved = false
        assertThrows(IOException::class.java) {
            saveConnectionWithReservations(connections, LocalPortStore(map), connection, validate = {}) { reserved = true }
        }
        assertFalse(reserved)
        assertTrue(connections.connections.value.isEmpty())
        assertNull(file.value)
        assertEquals(0, map.writes)
    }

    @Test fun `validation still occurs under the map gate before either file changes`() {
        val file = FaultFile()
        val map = FaultFile()
        val connections = ConnectionStore(file, MemoryStorage())
        assertThrows(IllegalArgumentException::class.java) {
            saveConnectionWithReservations(connections, LocalPortStore(map), connection,
                validate = { throw IllegalArgumentException("Synthetic pin conflict") }) { error("Must not reserve") }
        }
        assertEquals(0, file.writes)
        assertEquals(0, map.writes)
        assertTrue(connections.connections.value.isEmpty())
    }

    @Test fun `cancellation after authoritative save is never converted into a success callback`() {
        val file = FaultFile()
        val connections = ConnectionStore(file, MemoryStorage())
        assertThrows(java.util.concurrent.CancellationException::class.java) {
            saveConnectionWithReservations(connections, LocalPortStore(FaultFile()), connection, validate = {}) {
                throw java.util.concurrent.CancellationException("Synthetic disposed owner")
            }
        }
        assertEquals(listOf(connection), connections.connections.value)
    }

    @Test fun `successful save and reservation retain other profile entries`() {
        val file = FaultFile()
        val map = FaultFile().apply { value = """{"B/other:80":40001}""" }
        val ports = LocalPortStore(map)
        val result = saveConnectionWithReservations(ConnectionStore(file, MemoryStorage()), ports, connection, validate = {}) {
            reservePins(it, mapOf(profilePortKey("A", "off", 22) to 40000))
        }
        assertEquals(ConnectionSaveResult.SAVED, result)
        assertEquals(40000, ports.load()[profilePortKey("A", "off", 22)])
        assertEquals(40001, ports.load()["B/other:80"])
    }
}
