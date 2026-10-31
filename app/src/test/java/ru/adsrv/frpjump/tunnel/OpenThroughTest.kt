package ru.adsrv.frpjump.tunnel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenThroughTest {
    private val ssh = TunnelRef("p1", "dev-a", 22)
    private val otherPort = TunnelRef("p1", "dev-a", 2222)
    private val otherDevice = TunnelRef("p1", "dev-b", 22)
    private val otherProfile = TunnelRef("p2", "dev-a", 22)

    @Test
    fun `nothing open disconnects at once`() {
        val open = openThrough(emptyList(), emptyList(), ssh)
        assertEquals(OpenThrough(0, 0), open)
        assertFalse(open.any)
    }

    @Test
    fun `only live sessions on the same profile, device and port count`() {
        val sessions = listOf(
            SessionOn(ssh, live = true),
            SessionOn(ssh, live = true),
            SessionOn(ssh, live = false),
            SessionOn(otherPort, live = true),
            SessionOn(otherDevice, live = true),
            SessionOn(otherProfile, live = true),
        )
        assertEquals(OpenThrough(2, 0), openThrough(sessions, emptyList(), ssh))
    }

    @Test
    fun `an open file screen alone asks too`() {
        val open = openThrough(listOf(SessionOn(ssh, live = false)), listOf(ssh, otherDevice), ssh)
        assertEquals(OpenThrough(0, 1), open)
        assertTrue(open.any)
    }

    @Test
    fun `disconnect all counts every tunnel`() {
        val sessions = listOf(SessionOn(ssh, true), SessionOn(otherProfile, true), SessionOn(otherDevice, false))
        assertEquals(OpenThrough(2, 2), openThrough(sessions, listOf(ssh, otherPort), null))
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
}
