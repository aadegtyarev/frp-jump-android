package ru.adsrv.frpjump.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.tunnel.SessionOn
import ru.adsrv.frpjump.tunnel.TunnelRef

class FilesKeeperTest {
    private val wb = TunnelRef("p1", "wb7-test", 22)
    private val other = TunnelRef("p1", "wb8-test", 22)

    private class Fake(override val tunnel: TunnelRef?, override val user: String = "root") : FilesKeeper.Session {
        var left = 0
        var resumed = 0
        var closed = 0

        override fun leave() {
            left++
        }

        override fun resume() {
            resumed++
        }

        override fun close() {
            closed++
        }
    }

    private val made = mutableMapOf<Int, Fake>()
    private fun keeper(tunnelOf: (Int) -> TunnelRef? = { wb }, userOf: (Int) -> String = { "root" }) =
        FilesKeeper { id -> Fake(tunnelOf(id), userOf(id)).also { made[id] = it } }

    @Test
    fun `kept only while a live terminal is on the same tunnel`() {
        assertTrue(keepsFiles(wb, listOf(SessionOn(wb, live = true))))
        assertFalse(keepsFiles(wb, emptyList()))
        assertFalse(keepsFiles(wb, listOf(SessionOn(wb, live = false))))
        assertFalse(keepsFiles(wb, listOf(SessionOn(other, live = true))))
        assertFalse(keepsFiles(null, listOf(SessionOn(wb, live = true))))
    }

    @Test
    fun `leaving with no terminal open closes the session`() {
        val k = keeper()
        k.attach(1)
        k.release(1)
        assertEquals(1, made.getValue(1).closed)
        assertNull(k.kept(wb))
    }

    @Test
    fun `leaving with a terminal open keeps it and Files returns to it`() {
        val k = keeper()
        k.onTerminals(listOf(SessionOn(wb, live = true)))
        val s = k.attach(1)
        k.release(1)
        assertEquals(1, made.getValue(1).left)
        assertEquals(0, made.getValue(1).closed)
        assertEquals(1, k.kept(wb))
        assertNull(k.kept(other))
        assertSame(s, k.attach(1))
        assertEquals(1, made.getValue(1).resumed)
        // On a screen now: not offered again.
        assertNull(k.kept(wb))
    }

    @Test
    fun `the last terminal ending closes the kept session`() {
        val k = keeper()
        k.onTerminals(listOf(SessionOn(wb, live = true), SessionOn(wb, live = true)))
        k.attach(1)
        k.release(1)
        k.onTerminals(listOf(SessionOn(wb, live = true), SessionOn(wb, live = false)))
        assertEquals(0, made.getValue(1).closed)
        k.onTerminals(listOf(SessionOn(wb, live = false)))
        assertEquals(1, made.getValue(1).closed)
        assertNull(k.kept(wb))
    }

    @Test
    fun `a session on screen is not closed when terminals go`() {
        val k = keeper()
        k.onTerminals(listOf(SessionOn(wb, live = true)))
        k.attach(1)
        k.onTerminals(emptyList())
        assertEquals(0, made.getValue(1).closed)
        k.release(1)
        assertEquals(1, made.getValue(1).closed)
    }

    @Test
    fun `the terminal path reuses only the same login`() {
        val k = keeper(userOf = { if (it == 1) "root" else "admin" })
        k.onTerminals(listOf(SessionOn(wb, live = true)))
        k.attach(1)
        k.release(1)
        assertEquals(1, k.kept(wb, "root"))
        assertNull(k.kept(wb, "admin"))
    }

    @Test
    fun `one kept session per tunnel - the one left last`() {
        val k = keeper()
        k.onTerminals(listOf(SessionOn(wb, live = true)))
        k.attach(1)
        k.release(1)
        k.attach(2)
        k.release(2)
        assertEquals(1, made.getValue(1).closed)
        assertEquals(2, k.kept(wb))
    }

    @Test
    fun `a session whose login is gone is never kept`() {
        val k = keeper(tunnelOf = { null })
        k.onTerminals(listOf(SessionOn(wb, live = true)))
        k.attach(1)
        k.release(1)
        assertEquals(1, made.getValue(1).closed)
    }
}
