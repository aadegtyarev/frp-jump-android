package ru.adsrv.frpjump.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.devices.DeviceIcon

class DeviceGlyphsTest {
    @Test
    fun `every icon and the generic one have a glyph whose path parses`() {
        val glyphs = DeviceIcon.entries.map { it.glyph } + (null as DeviceIcon?).glyph
        assertEquals(DeviceIcon.entries.size + 1, glyphs.distinct().size)
        DeviceIcon.entries.forEach { it.caption }
    }
}
