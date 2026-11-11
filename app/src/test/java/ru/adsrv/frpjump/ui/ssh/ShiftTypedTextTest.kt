package ru.adsrv.frpjump.ui.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Soft-keyboard text with the sticky Shift, in the order of Termux v0.118.3 (read from `javap -c -p` of
 * `TerminalView$2.sendTextToTerminal` and `TerminalView.inputCodePoint(int, boolean, boolean)`): per code
 * point `readShiftKey()` (the client's: [Modifiers.takeShift]) then `Character.toUpperCase`, then
 * `inputCodePoint`, which reads `readControlKey()` / `readAltKey()` ([Modifiers.takeCtrl] / [Modifiers.takeAlt]),
 * calls the client's onCodePoint (no Shift there), maps Ctrl + a-z / A-Z to 1..26 and prefixes ESC for Alt.
 * So the app needs nothing but `readShiftKey() = takeShift()`.
 */
class ShiftTypedTextTest {
    private val modifiers = Modifiers()
    private val out = StringBuilder()

    private fun inputCodePoint(codePoint: Int) {
        val ctrl = modifiers.takeCtrl()
        val alt = modifiers.takeAlt()
        var cp = codePoint
        if (ctrl) {
            if (cp in 'a'.code..'z'.code) cp = cp - 'a'.code + 1
            else if (cp in 'A'.code..'Z'.code) cp = cp - 'A'.code + 1
        }
        if (alt) out.append('\u001b')
        out.appendCodePoint(cp)
    }

    private fun commit(text: String) = text.codePoints().forEach {
        inputCodePoint(if (modifiers.takeShift()) Character.toUpperCase(it) else it)
    }

    private fun assertReleased() = assertFalse(modifiers.ctrl || modifiers.alt || modifiers.shift)

    @Test
    fun shiftUppercasesTheFirstTypedLetterOnly() {
        modifiers.shift = true
        commit("ab")
        assertEquals("Ab", out.toString())
        assertReleased()
    }

    @Test
    fun ctrlShiftLetterIsTheControlByte() {
        modifiers.ctrl = true
        modifiers.shift = true
        commit("a")
        assertEquals("\u0001", out.toString())
        assertReleased()
    }

    @Test
    fun altShiftLetterIsEscAndCapital() {
        modifiers.alt = true
        modifiers.shift = true
        commit("a")
        assertEquals("\u001bA", out.toString())
        assertReleased()
    }

    @Test
    fun digitsAndSymbolsAreUnchanged() {
        modifiers.shift = true
        commit("1")
        modifiers.shift = true
        commit("|")
        assertEquals("1|", out.toString())
        assertReleased()
    }

    @Test
    fun aSymbolExtraKeyReleasesShiftAndTypesAsIs() {
        for (key in listOf(ExtraKey.PIPE, ExtraKey.TILDE, ExtraKey.SLASH, ExtraKey.DASH)) {
            modifiers.shift = true
            val press = pressFor(key, modifiers) as KeyPress.Typed
            assertEquals(key.label.codePointAt(0), press.codePoint)
            assertFalse(modifiers.shift)
        }
    }
}
