package ru.adsrv.frpjump.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.devices.DeviceIcon
import ru.adsrv.frpjump.devices.shownIcon

class DeviceGlyphsTest {
    @Test fun `readable glyphs keep the existing server wire identities`() {
        assertEquals("wirenboard", DeviceIcon.WIRENBOARD.wire)
        assertEquals("pc", DeviceIcon.PC.wire)
        assertEquals("WirenBoardMonogram", DeviceIcon.WIRENBOARD.glyph.name)
        assertEquals("Computer", DeviceIcon.PC.glyph.name)
    }

    @Test fun `visual update does not override explicit or unknown device choices`() {
        assertEquals(DeviceIcon.LINUX.glyph, shownIcon("linux", "wirenboard").glyph)
        assertEquals(DeviceIcon.WIRENBOARD.glyph, shownIcon(null, "wirenboard").glyph)
        assertEquals(DeviceIcon.PC.glyph, shownIcon("pc", "linux").glyph)
        assertEquals(DeviceGlyph, shownIcon("future-kind", "wirenboard").glyph)
    }

    @Test
    fun `every icon and the generic one have a glyph whose path parses`() {
        val glyphs = DeviceIcon.entries.map { it.glyph } + (null as DeviceIcon?).glyph
        assertEquals(DeviceIcon.entries.size + 1, glyphs.distinct().size)
        DeviceIcon.entries.forEach { it.caption }
    }
}
