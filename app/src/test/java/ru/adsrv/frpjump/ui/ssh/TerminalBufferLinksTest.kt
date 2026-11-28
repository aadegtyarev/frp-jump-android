package ru.adsrv.frpjump.ui.ssh

import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalOutput
import org.junit.Assert.*
import org.junit.Test

/** Link lookup against the same Termux buffer used by terminal taps and the on-screen menu. */
class TerminalBufferLinksTest {
    private val output = object : TerminalOutput() {
        override fun write(data: ByteArray, offset: Int, count: Int) = Unit
        override fun titleChanged(oldTitle: String?, newTitle: String?) = Unit
        override fun onCopyTextToClipboard(text: String?) = Unit
        override fun onPasteTextFromClipboard() = Unit
        override fun onBell() = Unit
        override fun onColorsChanged() = Unit
    }
    private fun emulator(columns: Int = 20, rows: Int = 3) = TerminalEmulator(output, columns, rows, 12, 24, 1000, null)
    private fun TerminalEmulator.feed(text: String) { val bytes = text.toByteArray(); append(bytes, bytes.size) }
    private fun row(grid: TapGrid, n: Int) = (0 until grid.columns).joinToString("") { grid.text(n, it) }

    @Test fun `plain wrapped link is tappable on both fragments and listed when its start is above viewport`() {
        val e = emulator(rows = 2); val address = "https://example.org/continued"
        e.feed("$address\r\nprompt")
        val g = BufferGrid(e)
        assertEquals(-1, g.firstRow); assertTrue(g.wraps(-1))
        assertEquals("https://example.org/", row(g, -1)); assertTrue(row(g, 0).startsWith("continued"))
        assertEquals(address, linkAt(g, false, -1, 3)); assertEquals(address, linkAt(g, false, 0, 3))
        assertEquals(listOf(address), linksOnScreen(g, false, 0))
    }

    @Test fun `plain wrapped link is complete in list when its end is below viewport`() {
        val e = emulator(rows = 2); val address = "https://example.org/continued"
        e.feed("before\r\n$address\r\nafter")
        val g = BufferGrid(e)
        assertTrue(g.wraps(-1))
        assertEquals(address, linkAt(g, false, -1, 4)); assertEquals(address, linkAt(g, false, 0, 3))
        assertEquals(listOf(address), linksOnScreen(g, false, -2))
    }

    @Test fun `tmux style row redraw joins margin fragments without joining a new URL`() {
        val e = emulator(columns = 20, rows = 3)
        val address = "https://example.org/continued"
        e.feed("\u001b[?1049h\u001b[1;1H${address.take(20)}\u001b[2;1H${address.drop(20)}\u001b[3;1Hprompt")
        val g = BufferGrid(e)
        assertFalse(g.wraps(0)); assertEquals(address, linkAt(g, true, 0, 5)); assertEquals(address, linkAt(g, true, 1, 3))
        assertEquals(listOf(address), linksOnScreen(g, true))
        val lines = emulator(columns = 20, rows = 3)
        lines.feed("\u001b[?1049h\u001b[1;1Hhttps://a.example/xx\u001b[2;1Hhttps://b.example/y\u001b[3;1Hprompt")
        val h = BufferGrid(lines)
        assertEquals("https://a.example/xx", linkAt(h, true, 0, 4))
        assertEquals("https://b.example/y", linkAt(h, true, 1, 4))
        assertEquals(listOf("https://a.example/xx", "https://b.example/y"), linksOnScreen(h, true))
    }
    @Test fun `indented parenthesized redraw joins only the corroborated continuation`() {
        val e = emulator(columns = 32, rows = 3)
        e.feed("\u001b[?1049h\u001b[1;1H  (https://art.example/abc123456\u001b[2;1H  suffix) next\u001b[3;1Hprompt")
        val g = BufferGrid(e)
        assertFalse(g.wraps(0)); assertEquals("  (https://art.example/abc123456", row(g, 0))
        val address = "https://art.example/abc123456suffix"
        assertEquals(address, linkAt(g, true, 0, 6)); assertEquals(address, linkAt(g, true, 1, 3))
        assertNull(linkAt(g, true, 1, 0)); assertNull(linkAt(g, true, 1, 1))
        assertEquals(listOf(address), linksOnScreen(g, true))
        assertEquals(address, (routeTap(g, false, true, 2, 0, 1, 3) as TapRoute.Link).text)
        assertEquals(address, describeLink(address).address); assertEquals(address, linkOpenTarget(address))
    }

    @Test fun `indented redraw with a new URL or missing enclosure stays separate`() {
        for (second in listOf("  https://new.example/x)", "  ordinary text", "   suffix) next", "  suffix without close")) {
            val e = emulator(columns = 32, rows = 3)
            e.feed("\u001b[?1049h\u001b[1;1H  (https://art.example/abc123456\u001b[2;1H$second\u001b[3;1Hprompt")
            val g = BufferGrid(e)
            assertEquals(second, "https://art.example/abc123456", linkAt(g, true, 0, 6))
            if (second.startsWith("  https://")) assertEquals("https://new.example/x", linkAt(g, true, 1, 5)) else assertNull(linkAt(g, true, 1, 3))
        }
    }

    @Test fun `real newline keeps full margin URLs separate and excludes offscreen links`() {
        val e = emulator(columns = 20, rows = 3)
        e.feed("https://a.example/xx\r\nhttps://b.example/y\r\nprompt")
        val g = BufferGrid(e)
        assertFalse(g.wraps(0))
        assertEquals("https://a.example/xx", linkAt(g, false, 0, 4))
        assertEquals("https://b.example/y", linkAt(g, false, 1, 4))
        assertEquals(listOf("https://a.example/xx", "https://b.example/y"), linksOnScreen(g, false))
        val off = emulator(columns = 20, rows = 2)
        off.feed("http://off.example/a\r\nfiller\r\nvisible\r\nbottom")
        assertTrue(linksOnScreen(BufferGrid(off), false).isEmpty())
    }

    @Test fun `viewport expansion preserves length cap without clipping oversized URLs`() {
        val prefix = "https://example.org/"
        for (size in listOf(MAX_LINK_CHARS, MAX_LINK_CHARS + 1)) {
            val address = prefix + "a".repeat(size - prefix.length)
            val e = emulator(columns = 20, rows = 2); e.feed("$address\r\nprompt")
            val g = BufferGrid(e)
            val shown = linksOnScreen(g, false)
            if (size == MAX_LINK_CHARS) assertEquals(listOf(address), shown) else assertTrue(shown.isEmpty())
        }
    }

    @Test fun `enclosed continuation remains whole when its prefix is in scrollback`() {
        val e = emulator(columns = 32, rows = 2)
        e.feed("  (https://art.example/abc123456\r\n  suffix) next\r\nprompt")
        val g = BufferGrid(e); val address = "https://art.example/abc123456suffix"
        assertEquals(-1, g.firstRow); assertFalse(g.wraps(-1))
        assertEquals(address, linkAt(g, true, -1, 6)); assertEquals(address, linkAt(g, true, 0, 3))
        assertEquals(listOf(address), linksOnScreen(g, true, 0))
    }

    @Test fun `quoted or parenthesized new URL at margin starts its own link`() {
        for ((open, close) in listOf("(" to ")", "'" to "'", "‘" to "’", "“" to "”", "\"" to "\"")) {
            val e = emulator(columns = 20, rows = 3)
            e.feed("\u001b[?1049h\u001b[1;1Hhttps://a.example/xx\u001b[2;1H${open}https://b.test/y$close\u001b[3;1Hprompt")
            val g = BufferGrid(e)
            assertEquals(open, "https://a.example/xx", linkAt(g, true, 0, 3))
            assertEquals(open, "https://b.test/y", linkAt(g, true, 1, 4))
            assertEquals(open, listOf("https://a.example/xx", "https://b.test/y"), linksOnScreen(g, true))
        }
    }

}
