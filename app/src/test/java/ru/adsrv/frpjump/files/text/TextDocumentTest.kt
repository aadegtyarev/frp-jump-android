package ru.adsrv.frpjump.files.text

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lines, rows of long lines, decoding and search. */
class TextDocumentTest {
    @Test
    fun `lines without their ends, no extra line after the last break`() {
        val doc = TextDocument("a\r\nbb\n\nc\n")
        assertEquals(4, doc.lineCount)
        assertEquals(listOf("a", "bb", "", "c"), (0 until doc.lineCount).map(doc::line))
        assertEquals(1, TextDocument("").lineCount)
        assertEquals(2, TextDocument("x\ny").lineCount)
    }

    @Test
    fun `a long line goes on in rows of at most ROW_CHARS`() {
        val long = "x".repeat(ROW_CHARS * 2 + 5)
        val doc = TextDocument("short\n$long\nend")
        assertEquals(3, doc.lineCount)
        assertEquals(5, doc.rowCount)
        assertEquals(listOf(0, 1, 1, 1, 2), (0 until doc.rowCount).map(doc::rowLine))
        assertEquals(listOf(5, ROW_CHARS, ROW_CHARS, 5, 3), (0 until doc.rowCount).map { doc.rowEnd(it) - doc.rowStart(it) })
        assertEquals(listOf(false, false, true, true, false), (0 until doc.rowCount).map(doc::isContinuation))
        assertEquals(ROW_CHARS, doc.widestRow)
        assertEquals(3, doc.rowAt(doc.lineStart(1) + ROW_CHARS * 2 + 1))
        assertEquals(0, doc.rowAt(0))
        assertEquals(4, doc.rowAt(doc.text.length - 1))
    }

    @Test
    fun `a 2 MB one-line file splits into rows quickly`() {
        val doc = TextDocument("{\"a\":1}".repeat(2 * 1024 * 1024 / 7))
        assertEquals(1, doc.lineCount)
        assertEquals((doc.text.length + ROW_CHARS - 1) / ROW_CHARS, doc.rowCount)
    }

    @Test
    fun `a character of two halves is not split between rows`() {
        val doc = TextDocument("x".repeat(ROW_CHARS - 1) + "😀" + "y")
        assertEquals(ROW_CHARS - 1, doc.rowEnd(0))
        assertTrue(doc.text[doc.rowStart(1)].isHighSurrogate())
    }

    @Test
    fun `tabs count wide for the unwrapped width`() {
        assertEquals(10, TextDocument("\tab").widestRow)
    }

    @Test
    fun `decoding replaces bad bytes and drops a byte order mark`() {
        val doc = TextDocument.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte(), 'a'.code.toByte(), 0xFF.toByte(), 'b'.code.toByte()))
        assertEquals("a�b", doc.text)
    }

    @Test
    fun `search ignores case, finds no overlaps, stops at the cap`() {
        val doc = TextDocument("Error error\nERROR")
        assertArrayEquals(intArrayOf(0, 6, 12), doc.find("error"))
        assertArrayEquals(intArrayOf(0, 2), TextDocument("aaaa").find("aa"))
        assertEquals(0, doc.find("").size)
        assertEquals(MAX_MATCHES, TextDocument("a".repeat(MAX_MATCHES + 50)).find("a").size)
        assertFalse(doc.find("nothing").isNotEmpty())
    }

    @Test
    fun `binary searches for a row's pieces and matches`() {
        val spans = intArrayOf(0, 2, 0, 5, 9, 1, 12, 13, 2)
        assertEquals(0, firstSpanAfter(spans, 0))
        assertEquals(3, firstSpanAfter(spans, 2))
        assertEquals(3, firstSpanAfter(spans, 8))
        assertEquals(6, firstSpanAfter(spans, 9))
        assertEquals(9, firstSpanAfter(spans, 13))
        val matches = intArrayOf(0, 10, 20)
        assertEquals(0, firstMatchAfter(matches, 3, 2))
        assertEquals(1, firstMatchAfter(matches, 3, 3))
        assertEquals(1, firstMatchAfter(matches, 3, 12))
        assertEquals(3, firstMatchAfter(matches, 3, 23))
    }

    @Test
    fun `control characters show as their signs, one for one`() {
        val text = "a\u001b[31mb\rc\td\u007f"
        val shown = shownText(text, 0, text.length)
        assertEquals("a\u241b[31mb\u240dc\td\u2421", shown)
        assertEquals(text.length, shown.length)
        assertEquals("plain", shownText("xplainx", 1, 6))
    }
}
