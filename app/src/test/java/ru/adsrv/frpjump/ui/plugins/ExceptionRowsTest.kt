package ru.adsrv.frpjump.ui.plugins

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.connections.Connection
import ru.adsrv.frpjump.mqtt.plugins.OwnEntry
import ru.adsrv.frpjump.ports.PortKind

/** «Свои настройки подключений»: the exceptions named by their connections. */
class ExceptionRowsTest {
    private val broker = Connection(id = "c1", device = "wb01", port = 1883, kind = PortKind.MQTT)
    private val home = Connection(id = "c2", device = "nas", port = 1883, kind = PortKind.MQTT)
    private val known = mapOf(
        "p1/c1" to KnownConnection(broker, icon = null, profileName = "Home", profileColor = "blue"),
        "p2/c2" to KnownConnection(home, icon = null, profileName = "Work", profileColor = "green"),
    )

    @Test
    fun `rows follow the entries' order with their state`() {
        val rows = exceptionRows(listOf(OwnEntry("p2/c2", "p2", "c2", on = true), OwnEntry("p1/c1", "p1", "c1", on = false)), known, manyProfiles = true)
        assertEquals(listOf("nas", "wb01"), rows.map { it.connection.device })
        assertEquals(listOf(true, false), rows.map { it.on })
        assertEquals("Work", rows[0].profileName)
        assertEquals("green", rows[0].profileColor)
    }

    @Test
    fun `one profile - no profile name or colour`() {
        val row = exceptionRows(listOf(OwnEntry("p1/c1", "p1", "c1", on = false)), known, manyProfiles = false).single()
        assertNull(row.profileName)
        assertNull(row.profileColor)
    }

    @Test
    fun `a connection not known yet is left out`() {
        assertEquals(emptyList<ExceptionRow>(), exceptionRows(listOf(OwnEntry("p3/c9", "p3", "c9", on = false)), known, manyProfiles = true))
    }
}
