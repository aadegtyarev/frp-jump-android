package ru.adsrv.frpjump.ui.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapEchoHoldTest {
    private var now = 0L
    private val hold = TapEchoHold { now }

    @Test
    fun `no arrows sent, no hold`() {
        assertFalse(hold.holds("line"))
    }

    @Test
    fun `taps wait while arrows are written and until the echo changes the line`() {
        hold.sending()
        assertTrue(hold.holds("before"))
        hold.sent("before")
        now += 100
        assertTrue(hold.holds("before"))
        // The echo came: the next tap counts from the new screen.
        assertFalse(hold.holds("after"))
        assertFalse(hold.holds("before"))
    }

    @Test
    fun `no echo within the limit, taps go again`() {
        hold.sending()
        hold.sent("same")
        now += TapEchoHold.ECHO_MS - 1
        assertTrue(hold.holds("same"))
        now += 1
        assertFalse(hold.holds("same"))
    }

    @Test
    fun `arrows go in pieces of whole codes`() {
        val pieces = arrowPieces("\u001b[D", 130, perPiece = 64)
        assertEquals(listOf(64, 64, 2), pieces.map { it.length / 3 })
        assertTrue(pieces.all { it.length % 3 == 0 && it.startsWith("\u001b[D") })
        assertEquals("\u001b[D".repeat(130), pieces.joinToString(""))
        assertEquals(emptyList<String>(), arrowPieces("\u001b[D", 0))
        // The cap fits: 2000 arrows of 3 bytes, each piece far below the 4 KiB queue.
        assertTrue(arrowPieces("\u001bOC", MAX_TAP_MOVES).all { it.length <= ARROWS_PER_PIECE * 3 })
    }
}
