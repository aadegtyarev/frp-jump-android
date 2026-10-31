package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SaysTunnelClosedTest {
    private val open = setOf(2, 3)

    @Test
    fun `the session shown went with its tunnel`() {
        assertTrue(saysTunnelClosed(1, 2, { it in open }, { it == 1 }))
        // The last one: nothing is shown after it.
        assertTrue(saysTunnelClosed(1, null, { false }, { it == 1 }))
    }

    @Test
    fun `closed by the owner, the terminal says nothing`() {
        assertFalse(saysTunnelClosed(1, 2, { it in open }, { false }))
    }

    @Test
    fun `another tab picked, or a reconnect, says nothing`() {
        // Picked: the one before is still open.
        assertFalse(saysTunnelClosed(3, 2, { it in open }, { true }))
        // Reconnect: a new id in its place, the old one closed by the screen.
        assertFalse(saysTunnelClosed(1, 4, { it in open }, { false }))
        assertFalse(saysTunnelClosed(null, 2, { it in open }, { true }))
        assertFalse(saysTunnelClosed(2, 2, { it in open }, { true }))
    }
}
