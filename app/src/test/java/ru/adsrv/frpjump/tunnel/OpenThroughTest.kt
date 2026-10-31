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
}
