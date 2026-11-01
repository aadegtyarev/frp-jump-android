package com.termux.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The emulator's private bracketed-paste flag, read as the remote side sets it (DECSET / DECRST 2004). */
class BracketedPasteTest {
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private val emulator = TerminalEmulator(output, 80, 24, 12, 24, 100, null)

    private fun feed(text: String) {
        val bytes = text.toByteArray()
        emulator.append(bytes, bytes.size)
    }

    @Test
    fun offUntilTheRemoteSideTurnsItOn() {
        assertFalse(bracketedPasteOn(emulator))
        feed("\u001b[?2004h")
        assertTrue(bracketedPasteOn(emulator))
        feed("\u001b[?2004l")
        assertFalse(bracketedPasteOn(emulator))
    }

    @Test
    fun noEmulatorIsOff() {
        assertFalse(bracketedPasteOn(null))
    }
}
