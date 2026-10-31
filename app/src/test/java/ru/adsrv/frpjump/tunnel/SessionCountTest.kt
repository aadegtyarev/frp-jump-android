package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCountTest {
    private val ssh = TunnelRef("p1", "dev-a", 22)
    private val otherPort = TunnelRef("p1", "dev-a", 2222)
    private val otherDevice = TunnelRef("p1", "dev-b", 22)
    private val otherProfile = TunnelRef("p2", "dev-a", 22)

    @Test
    fun `a row's terminal count leaves MQTT sessions out`() {
        val open = listOf(SessionOn(ssh, live = true), SessionOn(ssh, live = true, SessionKind.MQTT))
        assertEquals(SessionCount(1, 0), sessionsThrough(open, ssh))
    }

    @Test
    fun `a row counts the sessions through its own tunnel only, and the ended ones among them`() {
        val open = listOf(
            SessionOn(ssh, live = true),
            SessionOn(ssh, live = false),
            SessionOn(ssh, live = true),
            SessionOn(otherPort, live = true),
            SessionOn(otherDevice, live = false),
            SessionOn(otherProfile, live = true),
        )
        assertEquals(SessionCount(3, 1), sessionsThrough(open, ssh))
        assertEquals(SessionCount(1, 0), sessionsThrough(open, otherPort))
        assertEquals(SessionCount(1, 1), sessionsThrough(open, otherDevice))
        assertEquals(SessionCount.NONE, sessionsThrough(open, TunnelRef("p3", "dev-a", 22)))
        assertEquals(SessionCount.NONE, sessionsThrough(emptyList(), ssh))
    }

    @Test
    fun `the pill shows one session or an ended one, from two the button counts`() {
        assertFalse(SessionCount.NONE.showsPill)
        assertTrue(SessionCount(1, 0).showsPill)
        assertFalse(SessionCount(2, 0).showsPill)
        assertTrue(SessionCount(3, 1).showsPill)
    }
}
