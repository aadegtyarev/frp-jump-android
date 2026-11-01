package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TmuxNamesTest {
    @Test
    fun `the install code is made once and kept`() {
        val storage = MemoryStorage()
        val first = TmuxNames(storage, Random(1))
        val code = first.install
        assertTrue(code, Regex("[0-9a-f]{6}").matches(code))
        assertEquals(code, first.install)
        // The app restarted: another random source, the same code.
        assertEquals(code, TmuxNames(storage, Random(2)).install)
    }

    @Test
    fun `names follow the pattern and are never given twice, across restarts`() {
        val storage = MemoryStorage()
        val names = TmuxNames(storage, Random(1))
        val a = names.next()
        val b = names.next()
        val c = TmuxNames(storage, Random(2)).next()
        assertEquals("frpjump-${names.install}-1", a)
        assertEquals("frpjump-${names.install}-2", b)
        assertEquals("frpjump-${names.install}-3", c)
        listOf(a, b, c).forEach { assertTrue(it, isTmuxName(it)) }
    }

    @Test
    fun `an unreadable or foreign file starts over with a valid code`() {
        for (bad in listOf("{", "{\"install\":\"zz\",\"next\":4}", "{\"install\":\"abcdef\",\"next\":0}")) {
            val names = TmuxNames(MemoryStorage(bad), Random(3))
            assertTrue(bad, isTmuxName(names.next()))
        }
    }

    @Test
    fun `a tab keeps its name for every connection, Reconnect hands it over, another tab gets its own`() {
        val names = TmuxNames(MemoryStorage(), Random(1))
        val tab = TabTmux(names)
        assertEquals(null, tab.assigned)
        val name = tab.name()
        // Each try after a drop asks again: the same session, so it re-attaches.
        assertEquals(name, tab.name())
        assertEquals(name, tab.assigned)
        // Reconnect builds a new session in the same tab with the old name.
        assertEquals(name, TabTmux(names, tab.assigned).name())
        assertNotEquals(name, TabTmux(names).name())
    }
}
