package ru.adsrv.frpjump.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import ru.adsrv.frpjump.api.OwnedDevice
import java.time.Instant

class DeviceIconTest {
    @Test
    fun `every icon of the server's set is known by its wire name`() {
        val wire = listOf("linux", "wirenboard", "server", "pc", "laptop", "router", "sbc", "camera", "home", "phone")
        assertEquals(wire, wire.map { DeviceIcon.fromWire(it)?.wire })
    }

    @Test
    fun `the owner's choice wins, then the detected kind`() {
        assertEquals(DeviceIcon.ROUTER, shownIcon("router", "wirenboard"))
        assertEquals(DeviceIcon.WIRENBOARD, shownIcon(null, "wirenboard"))
    }

    @Test
    fun `neither set, or an unknown value, is the generic glyph`() {
        assertNull(shownIcon(null, null))
        assertNull(shownIcon(null, "toaster"))
        // An owner's choice this app does not know is not replaced by the detected kind.
        assertNull(shownIcon("toaster", "wirenboard"))
    }

    private val now = Instant.parse("2026-11-01T10:00:00Z")
    private val wb = OwnedDevice("wb01", "2026-10-01T00:00:00", null, true, label = "Щиток, гараж", icon = null, kind = "wirenboard")
    private val blank = OwnedDevice("pc", "2026-10-01T00:00:00", null, true, label = "  ", kind = "pc")

    @Test
    fun `rows carry the label and icon when the server supports them`() {
        val (row, other) = deviceRows(listOf(wb, blank), "phone", now, now, stale = false, display = true)
        assertEquals("Щиток, гараж", row.label)
        assertEquals(DeviceIcon.WIRENBOARD, row.icon)
        assertNull(other.label)
        assertEquals(DeviceIcon.PC, other.icon)
    }

    @Test
    fun `without the feature rows show no label and the generic glyph`() {
        val row = deviceRows(listOf(wb), "phone", now, now, stale = false, display = false).single()
        assertNull(row.label)
        assertNull(row.icon)
    }
}
