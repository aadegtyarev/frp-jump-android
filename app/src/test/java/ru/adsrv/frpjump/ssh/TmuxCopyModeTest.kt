package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxCopyModeTest {
    private fun b(s: String) = s.toByteArray(Charsets.ISO_8859_1)

    private val sgrUp = b("\u001b[<64;10;5M")
    private val legacyUp = b("\u001b[M" + (32 + 64).toChar() + "!!")
    private val key = b("l")

    private fun CopyModeGuard.send(bytes: ByteArray, typed: Boolean = false): Boolean {
        if (typed) ownerTyped()
        return beforeWrite(bytes, bytes.size)
    }

    @Test
    fun `wheel up is recognised in both encodings, nothing else is`() {
        assertTrue(hasWheelUp(sgrUp, sgrUp.size))
        assertTrue(hasWheelUp(legacyUp, legacyUp.size))
        assertFalse(hasWheelUp(b("\u001b[<65;10;5M"), 11))
        assertFalse(hasWheelUp(b("\u001b[<0;10;5M"), 10))
        assertFalse(hasWheelUp(b("\u001b[A"), 3))
        assertFalse(hasWheelUp(b("<64;"), 4))
        // Only the first count bytes count.
        assertFalse(hasWheelUp(sgrUp, 4))
    }

    @Test
    fun `the first typed key after scrolling up leaves copy mode first, once`() {
        val guard = CopyModeGuard().apply { active = true }
        assertFalse(guard.send(sgrUp))
        assertFalse(guard.send(sgrUp))
        assertTrue(guard.send(key, typed = true))
        assertFalse(guard.send(key, typed = true))
    }

    @Test
    fun `typing without scrolling, or the terminal's own replies, cost nothing`() {
        val guard = CopyModeGuard().apply { active = true }
        assertFalse(guard.send(key, typed = true))
        guard.send(legacyUp)
        // A reply of the terminal (not typed) after a scroll: no exec.
        assertFalse(guard.send(b("\u001b[0n")))
        assertTrue(guard.send(key, typed = true))
    }

    @Test
    fun `a plain shell, or a new connection, has no copy mode to leave`() {
        val plain = CopyModeGuard()
        plain.send(sgrUp)
        assertFalse(plain.send(key, typed = true))
        val guard = CopyModeGuard().apply { active = true }
        guard.send(sgrUp)
        guard.active = true
        assertFalse(guard.send(key, typed = true))
    }
}
