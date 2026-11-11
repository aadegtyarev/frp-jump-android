package ru.adsrv.frpjump.ui.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * [StickyShiftHook] driven in the order Termux v0.118.3's `TerminalView.inputCodePoint(int, boolean, boolean)`
 * calls its client (read from its bytecode): Ctrl = flag || readControlKey(), Alt = flag || readAltKey()
 * (each only when the flag is false), then onCodePoint(codePoint, ctrl); if that returns true, stop; else
 * Ctrl maps a-z and A-Z to 1..26, and Alt prefixes ESC.
 */
class StickyShiftHookTest {
    private val modifiers = Modifiers()
    private val out = StringBuilder()
    private val hook: StickyShiftHook = StickyShiftHook(modifiers) { cp, ctrl, alt -> inputCodePoint(cp, ctrl, alt) }

    private fun inputCodePoint(codePoint: Int, ctrlFlag: Boolean, altFlag: Boolean) {
        val ctrl = ctrlFlag || modifiers.takeCtrl()
        val alt = altFlag || hook.readAlt()
        if (hook.onCodePoint(codePoint, ctrl)) return
        var cp = codePoint
        if (ctrl) {
            if (cp in 'a'.code..'z'.code) cp = cp - 'a'.code + 1
            else if (cp in 'A'.code..'Z'.code) cp = cp - 'A'.code + 1
        }
        if (alt) out.append('\u001b')
        out.appendCodePoint(cp)
    }

    private fun type(text: String) = text.codePoints().forEach { inputCodePoint(it, false, false) }

    private fun assertReleased() = assertFalse(modifiers.ctrl || modifiers.alt || modifiers.shift)

    @Test
    fun shiftUppercasesTheNextLetterOnly() {
        modifiers.shift = true
        type("ab")
        assertEquals("Ab", out.toString())
        assertReleased()
    }

    @Test
    fun shiftUppercasesNonLatinLetters() {
        modifiers.shift = true
        type("яя")
        assertEquals("Яя", out.toString())
    }

    @Test
    fun shiftKeepsDigitsAndSymbolsAndIsReleased() {
        modifiers.shift = true
        type("1")
        modifiers.shift = true
        type("|")
        assertEquals("1|", out.toString())
        assertReleased()
    }

    @Test
    fun altAndShiftGiveEscAndCapital() {
        modifiers.alt = true
        modifiers.shift = true
        type("a")
        assertEquals("\u001bA", out.toString())
        assertReleased()
    }

    @Test
    fun ctrlAndShiftGiveTheControlByte() {
        modifiers.ctrl = true
        modifiers.shift = true
        type("a")
        assertEquals("\u0001", out.toString())
        assertReleased()
    }

    @Test
    fun anAltUsedEarlierNeverReachesALaterShiftedLetter() {
        modifiers.alt = true
        type("a")
        modifiers.shift = true
        type("b")
        assertEquals("\u001ba" + "B", out.toString())
        assertReleased()
    }

    @Test
    fun aStaleAltReadWithoutACodePointIsDroppedByTheNextOne() {
        modifiers.alt = true
        hook.readAlt() // e.g. a key-code key read it, and no onCodePoint followed
        modifiers.shift = true
        type("b")
        assertEquals("B", out.toString())
    }

    @Test
    fun withoutShiftTheHookDoesNothing() {
        type("a")
        assertEquals("a", out.toString())
    }
}
