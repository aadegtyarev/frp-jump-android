package ru.adsrv.frpjump.devices

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenMarksTest {
    @Test
    fun `a list stays wanted until the last screen showing it lets go`() {
        val marks = ScreenMarks()
        val devices = Any()
        val connections = Any()
        assertFalse(marks.any)
        marks.set(devices, true)
        marks.set(connections, true)
        // Devices leaves after Connections came: Connections still wants it.
        marks.set(devices, false)
        assertTrue(marks.any)
        marks.set(connections, false)
        assertFalse(marks.any)
    }

    @Test
    fun `marking twice or letting go twice counts once`() {
        val marks = ScreenMarks()
        val screen = Any()
        marks.set(screen, true)
        marks.set(screen, true)
        marks.set(screen, false)
        assertFalse(marks.any)
        marks.set(screen, false)
        assertFalse(marks.any)
    }
}
