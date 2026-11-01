package ru.adsrv.frpjump.ui.ssh

import com.termux.terminal.KeyHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The bytes the extra keys send, through the emulator's own key table, and how they use up a held Ctrl / Alt. */
class KeyPressTest {
    private val modifiers = Modifiers()

    /** What [key] writes to the remote side (normal cursor and keypad modes). */
    private fun bytes(key: ExtraKey): String = when (val press = pressFor(key, modifiers)) {
        is KeyPress.Control -> String(Character.toChars(press.byte))
        is KeyPress.Code -> KeyHandler.getCode(press.keyCode, press.keyMod, false, false)
        is KeyPress.Typed -> String(Character.toChars(press.codePoint))
    }

    @Test
    fun backspaceIsDel() {
        assertEquals("\u007f", bytes(ExtraKey.BACKSPACE))
    }

    @Test
    fun ctrlBackspaceIsBs() {
        modifiers.ctrl = true
        assertEquals("\b", bytes(ExtraKey.BACKSPACE))
        assertFalse(modifiers.ctrl)
    }

    @Test
    fun enterIsCr() {
        assertEquals("\r", bytes(ExtraKey.ENTER))
    }

    @Test
    fun altPrefixesEsc() {
        modifiers.alt = true
        assertEquals("\u001b\r", bytes(ExtraKey.ENTER))
        assertFalse(modifiers.alt)
        modifiers.alt = true
        assertEquals("\u001b\u007f", bytes(ExtraKey.BACKSPACE))
    }

    @Test
    fun readyCombosAreTheirControlBytes() {
        assertEquals("\u0003", bytes(ExtraKey.CTRL_C))
        assertEquals("\u0004", bytes(ExtraKey.CTRL_D))
        assertEquals("\u001a", bytes(ExtraKey.CTRL_Z))
        assertEquals("\u0012", bytes(ExtraKey.CTRL_R))
        assertEquals("\u000c", bytes(ExtraKey.CTRL_L))
    }

    @Test
    fun aReadyComboUsesUpTheHoldWithoutApplyingIt() {
        modifiers.ctrl = true
        modifiers.alt = true
        assertEquals("\u0003", bytes(ExtraKey.CTRL_C))
        assertFalse(modifiers.ctrl)
        assertFalse(modifiers.alt)
    }

    @Test
    fun aHeldModifierGoesWithTheNextKeyOnly() {
        modifiers.ctrl = true
        assertEquals(KeyPress.Code(ExtraKey.LEFT.keyCode!!, KeyHandler.KEYMOD_CTRL), pressFor(ExtraKey.LEFT, modifiers))
        assertEquals(KeyPress.Code(ExtraKey.LEFT.keyCode!!, 0), pressFor(ExtraKey.LEFT, modifiers))
    }

    @Test
    fun aCharacterLeavesTheHoldForTheView() {
        modifiers.ctrl = true
        assertEquals(KeyPress.Typed('|'.code), pressFor(ExtraKey.PIPE, modifiers))
        assertTrue(modifiers.ctrl)
    }
}
