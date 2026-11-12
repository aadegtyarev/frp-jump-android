package ru.adsrv.frpjump.assistant.chat

import org.junit.Assert.*
import org.junit.Test

/** Explicit code-point fixtures: no category is derived from the function under test. */
class CommandDisplayTest {
    private fun esc(cp: Int) = "⟨U+%04X⟩".format(cp)
    private fun s(cp: Int) = String(Character.toChars(cp))

    @Test fun `plain commands with line breaks spaces and ordinary text are shown unchanged`() {
        for (c in listOf("uptime", "ls -la /tmp\ndf -h", "echo 'ünï — 你好 🌍 привет'", "a  b\n\nc", "", "é á̂")) assertEquals(c, displayCommand(c))
    }

    @Test fun `tab carriage return escape NUL and delete become visible glyphs and the line break stays`() {
        assertEquals("a␉b", displayCommand("a\tb"))
        assertEquals("safe␍ rm -rf /", displayCommand("safe\r rm -rf /"))
        assertEquals("one␍\ntwo", displayCommand("one\r\ntwo"))
        assertEquals("␛[2K␀␡", displayCommand("\u001B[2K\u0000\u007F"))
    }

    @Test fun `every bidi mark override and isolate is escaped`() {
        for (cp in listOf(0x061C, 0x200E, 0x200F, 0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069))
            assertEquals("U+%04X".format(cp), "ls A${esc(cp)}B", displayCommand("ls A${s(cp)}B"))
    }

    @Test fun `every default-ignorable code point of the required ranges is escaped`() {
        val fixtures = listOf(
            0x00AD, 0x034F, 0x115F, 0x1160, 0x17B4, 0x17B5, 0x180B, 0x180C, 0x180D, 0x180E, 0x180F,
            0x200B, 0x200C, 0x200D, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0x206A, 0x206F, 0x3164, 0xFE00, 0xFE08, 0xFE0F, 0xFEFF, 0xFFA0,
            0xFFF0, 0xFFF8, 0x1BCA0, 0x1BCA3, 0x1D173, 0x1D17A, 0xE0001, 0xE0020, 0xE007F, 0xE0100, 0xE0150, 0xE01EF, 0xE0FFF,
        )
        for (cp in fixtures) assertEquals("U+%04X".format(cp), "x${esc(cp)}y", displayCommand("x${s(cp)}y"))
    }

    @Test fun `the braille blank and other look-alike blanks are escaped`() {
        for (cp in listOf(0x2800, 0x00A0, 0x1680, 0x2000, 0x2003, 0x200A, 0x202F, 0x205F, 0x3000, 0x2028, 0x2029, 0x0085, 0x2007))
            assertEquals("U+%04X".format(cp), "a${esc(cp)}b", displayCommand("a${s(cp)}b"))
    }

    @Test fun `private use unassigned and lone surrogates are escaped and a valid pair is not`() {
        assertEquals(esc(0xE000), displayCommand("")); assertEquals(esc(0x0378), displayCommand("͸"))
        assertEquals(esc(0xD800), displayCommand("\uD800")); assertEquals(esc(0xDFFF), displayCommand("\uDFFF"))
        assertEquals("a${esc(0xD83C)}b", displayCommand("a\uD83Cb")); assertEquals("a${esc(0xDF0D)}b", displayCommand("a\uDF0Db"))
        assertEquals("🌍", displayCommand("🌍"))
    }

    @Test fun `combining marks beyond the second in a run are escaped`() {
        val m = "́"
        assertEquals("a$m$m", displayCommand("a$m$m"))
        assertEquals("a$m$m${esc(0x301)}", displayCommand("a$m$m$m"))
        assertEquals("a$m$m" + esc(0x301).repeat(3), displayCommand("a$m$m$m$m$m"))
        assertEquals("a$m${m}b$m$m", displayCommand("a$m${m}b$m$m"))
        assertEquals("á̂${esc(0x20D0)}${esc(0x0316)}", displayCommand("á̖̂⃐"))
    }

    @Test fun `the characters of our own escapes are escaped so a typed look-alike cannot pass for one`() {
        val typed = displayCommandSpans("rm ⟨U+202E⟩ x")
        assertTrue("every escape span is ours: the typed brackets are escaped too", typed.filter { it.escape }.map { it.text } == listOf(esc(0x27E8), esc(0x27E9)))
        assertEquals("rm ${esc(0x27E8)}U+202E${esc(0x27E9)} x", displayCommand("rm ⟨U+202E⟩ x"))
        for (cp in listOf(0x2409, 0x240D, 0x241B, 0x2400, 0x2421)) assertEquals(esc(cp), displayCommand(s(cp)))
    }

    @Test fun `spans mark exactly the escapes`() {
        val spans = displayCommandSpans("ab‮cd\tef")
        assertEquals(listOf("ab", esc(0x202E), "cd", "␉", "ef"), spans.map { it.text })
        assertEquals(listOf(false, true, false, true, false), spans.map { it.escape })
        assertEquals(emptyList<DisplaySpan>(), displayCommandSpans(""))
    }

    @Test fun `strong right-to-left letters are detected and nothing else is`() {
        for (c in listOf("א", "x א y", "ا", "ls مرحبا", ": א; rm -rf ./* x ;ב; cd /tmp/scratch")) assertTrue(c, containsStrongRtl(c))
        for (c in listOf("", "uptime", "привет 你好", "123 456 ١٢", "a‮b", "ls ./*")) assertFalse(c, containsStrongRtl(c))
    }

    @Test fun `the line count is the original lines and ignores one final break`() {
        assertEquals(0, commandLineCount("")); assertEquals(1, commandLineCount("uptime")); assertEquals(1, commandLineCount("uptime\n"))
        assertEquals(16, commandLineCount("uptime" + "\n".repeat(15) + "curl x|sh")); assertEquals(3, commandLineCount("a\n\nb"))
    }
}
