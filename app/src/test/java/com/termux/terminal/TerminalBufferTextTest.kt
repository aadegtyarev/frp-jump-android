package com.termux.terminal

import org.junit.Assert.assertEquals
import org.junit.Test
import ru.adsrv.frpjump.assistant.terminalBufferText

/** What the assistant reads of a real Termux buffer: the screen and the asked-for history, nothing typed. */
class TerminalBufferTextTest {
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }

    private fun emulator(columns: Int = 20, rows: Int = 4) = TerminalEmulator(output, columns, rows, 12, 24, 100, null)
    private fun TerminalEmulator.feed(text: String) { val bytes = text.toByteArray(); append(bytes, bytes.size) }

    @Test fun `history and screen, the requested number of history rows`() {
        val e = emulator()
        e.feed((1..10).joinToString("\r\n") { "line $it" })
        // 4 screen rows show lines 7..10; 6 rows went to history.
        assertEquals("line 7\nline 8\nline 9\nline 10", terminalBufferText(e, 0).trimEnd())
        assertEquals("line 5\nline 6\nline 7\nline 8\nline 9\nline 10", terminalBufferText(e, 2).trimEnd())
        assertEquals((1..10).joinToString("\n") { "line $it" }, terminalBufferText(e, 1000).trimEnd())
    }

    @Test fun `wrapped rows are joined and trailing spaces dropped`() {
        val e = emulator(columns = 10, rows = 3)
        e.feed("0123456789abcdef   \r\nnext")
        assertEquals("0123456789abcdef\nnext", terminalBufferText(e, 5).trimEnd())
    }

    @Test fun `an empty screen is empty text`() {
        assertEquals("", terminalBufferText(emulator(), 100).trim())
    }

    @Test fun `a full-screen program's own screen has no history`() {
        val e = emulator()
        e.feed("old 1\r\nold 2\r\nold 3\r\nold 4\r\nold 5\r\n\u001B[?1049h\u001B[Hvim screen")
        assertEquals("vim screen", terminalBufferText(e, 1000).trim())
    }
}
