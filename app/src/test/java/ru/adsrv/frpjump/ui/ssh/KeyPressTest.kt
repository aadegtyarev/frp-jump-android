package ru.adsrv.frpjump.ui.ssh

import android.view.KeyEvent
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

    @Test
    fun theFirstGroupKeepsItsOrderWithShiftAfterAlt() {
        assertEquals(listOf("ESC", "TAB", "Ctrl", "Alt", "Shift", "CTRL_D", "CTRL_Z", "CTRL_R"), comboSlotNames())
    }

    @Test
    fun shiftTabIsBackTab() {
        modifiers.shift = true
        assertEquals("\u001b[Z", bytes(ExtraKey.TAB))
        assertFalse(modifiers.shift)
        assertEquals("\t", bytes(ExtraKey.TAB))
    }

    @Test
    fun shiftArrowAndHomeUseTheEmulatorsEncoding() {
        modifiers.shift = true
        assertEquals("\u001b[1;2A", bytes(ExtraKey.UP))
        modifiers.shift = true
        assertEquals("\u001b[1;2D", bytes(ExtraKey.LEFT))
        modifiers.shift = true
        assertEquals("\u001b[1;2H", bytes(ExtraKey.HOME))
        assertFalse(modifiers.shift)
    }

    @Test
    fun ctrlShiftArrowIsModifierSix() {
        modifiers.ctrl = true
        modifiers.shift = true
        assertEquals("\u001b[1;6A", bytes(ExtraKey.UP))
        assertFalse(modifiers.ctrl)
        assertFalse(modifiers.shift)
    }

    @Test
    fun aReadyComboUsesUpShiftToo() {
        modifiers.shift = true
        assertEquals("\u0004", bytes(ExtraKey.CTRL_D))
        assertFalse(modifiers.shift)
    }

    @Test
    fun modifiersClearReleasesAllThree() {
        modifiers.ctrl = true
        modifiers.alt = true
        modifiers.shift = true
        modifiers.clear()
        assertFalse(modifiers.ctrl || modifiers.alt || modifiers.shift)
    }

    @Test
    fun shiftPageKeysAreSentWithoutShiftBecauseTermuxWouldScrollOrSendArrows() {
        // handleKeyCodeAction turns KEYMOD_SHIFT + PgUp / PgDn (keyCode 92 / 93) into doScroll(rows).
        for (key in listOf(ExtraKey.PAGE_UP, ExtraKey.PAGE_DOWN)) {
            modifiers.shift = true
            assertEquals(KeyPress.Code(key.keyCode!!, 0), pressFor(key, modifiers))
            assertFalse(modifiers.shift)
        }
        modifiers.ctrl = true
        modifiers.alt = true
        modifiers.shift = true
        assertEquals(KeyPress.Code(KeyEvent.KEYCODE_PAGE_UP, KeyHandler.KEYMOD_CTRL or KeyHandler.KEYMOD_ALT), pressFor(ExtraKey.PAGE_UP, modifiers))
        assertFalse(modifiers.shift)
    }

    @Test
    fun otherKeyCodeKeysCarryShiftToTheEmulator() {
        val shifted = listOf(ExtraKey.TAB, ExtraKey.ESC, ExtraKey.ENTER, ExtraKey.BACKSPACE, ExtraKey.UP, ExtraKey.DOWN,
            ExtraKey.LEFT, ExtraKey.RIGHT, ExtraKey.HOME, ExtraKey.END)
        for (key in shifted) {
            modifiers.shift = true
            assertEquals("$key", KeyPress.Code(key.keyCode!!, KeyHandler.KEYMOD_SHIFT), pressFor(key, modifiers))
            assertFalse(modifiers.shift)
        }
    }

    @Test
    fun shiftChangesNoBytesOfEscEnterAndBackspace() {
        for (key in listOf(ExtraKey.ESC, ExtraKey.ENTER, ExtraKey.BACKSPACE)) {
            val plain = bytes(key)
            modifiers.shift = true
            assertEquals("$key", plain, bytes(key))
        }
    }

    @Test
    fun altShiftTabIsBackTabAndShiftEndUsesTheEmulatorsEncoding() {
        modifiers.alt = true
        modifiers.shift = true
        assertEquals("\u001b[Z", bytes(ExtraKey.TAB))
        modifiers.shift = true
        assertEquals("\u001b[1;2F", bytes(ExtraKey.END))
    }

    @Test
    fun aSymbolKeyIsTypedAsIsAndReleasesShiftButNotCtrlOrAlt() {
        modifiers.shift = true
        modifiers.ctrl = true
        assertEquals(KeyPress.Typed('~'.code), pressFor(ExtraKey.TILDE, modifiers))
        assertFalse(modifiers.shift)
        assertTrue(modifiers.ctrl)
    }
}
