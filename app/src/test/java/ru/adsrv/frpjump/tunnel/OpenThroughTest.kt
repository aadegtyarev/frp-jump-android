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
        assertEquals(OpenThrough(0, 0, 0), open)
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
        assertEquals(OpenThrough(2, 0, 0), openThrough(sessions, emptyList(), ssh))
    }

    @Test
    fun `an open file screen alone asks too`() {
        val open = openThrough(listOf(SessionOn(ssh, live = false)), listOf(ssh, otherDevice), ssh)
        assertEquals(OpenThrough(0, 0, 1), open)
        assertTrue(open.any)
    }

    @Test
    fun `disconnect all counts every tunnel`() {
        val sessions = listOf(SessionOn(ssh, true), SessionOn(otherProfile, true), SessionOn(otherDevice, false))
        assertEquals(OpenThrough(2, 0, 2), openThrough(sessions, listOf(ssh, otherPort), null))
    }

    @Test
    fun `disconnecting a profile counts its own tunnels only`() {
        val sessions = listOf(SessionOn(ssh, true), SessionOn(otherDevice, true), SessionOn(otherPort, false), SessionOn(otherProfile, true))
        assertEquals(OpenThrough(2, 0, 1), openOnProfile(sessions, listOf(otherPort, otherProfile), "p1"))
        assertEquals(OpenThrough(1, 0, 1), openOnProfile(sessions, listOf(otherPort, otherProfile), "p2"))
    }

    private val broker = TunnelRef("p1", "dev-a", 1883)

    @Test
    fun `MQTT sessions count apart from terminals, live ones only`() {
        val sessions = listOf(
            SessionOn(ssh, live = true),
            SessionOn(broker, live = true, SessionKind.MQTT),
            SessionOn(TunnelRef("p1", "dev-b", 1883), live = true, SessionKind.MQTT),
            SessionOn(TunnelRef("p2", "dev-a", 1883), live = false, SessionKind.MQTT),
        )
        assertEquals(OpenThrough(0, 1, 0), openThrough(sessions, emptyList(), broker))
        assertEquals(OpenThrough(1, 2, 0), openThrough(sessions, emptyList(), null))
        assertEquals(OpenThrough(1, 2, 0), openOnProfile(sessions, emptyList(), "p1"))
        assertEquals(OpenThrough(0, 0, 0), openOnProfile(sessions, emptyList(), "p2"))
        assertTrue(openThrough(sessions, emptyList(), broker).any)
        assertEquals(3, openThrough(sessions, emptyList(), null).sessions)
    }

    @Test
    fun `the warning names terminals, MQTT sessions or both, and nothing for none`() {
        fun say(open: OpenThrough) = closingSentence(open, { "T$it" }, { "M$it" }, { t, m -> "T$t+M$m" })
        assertEquals(null, say(OpenThrough(0, 0, 2)))
        assertEquals("T2", say(OpenThrough(2, 0, 0)))
        assertEquals("M1", say(OpenThrough(0, 1, 1)))
        assertEquals("T2+M1", say(OpenThrough(2, 1, 0)))
    }

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
