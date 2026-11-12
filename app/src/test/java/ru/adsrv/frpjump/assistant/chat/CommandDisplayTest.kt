package ru.adsrv.frpjump.assistant.chat

import org.junit.Assert.*
import org.junit.Test

class CommandDisplayTest {
    @Test fun `plain commands with line breaks and spaces are shown unchanged`() {
        for (c in listOf("uptime", "ls -la /tmp\ndf -h", "echo 'ünï — 你好 🌍'", "a  b\n\nc", "")) assertEquals(c, displayCommand(c))
    }

    @Test fun `tab and carriage return become visible glyphs and the line break stays`() {
        assertEquals("a␉b", displayCommand("a\tb"))
        assertEquals("safe␍ rm -rf /", displayCommand("safe\r rm -rf /"))
        assertEquals("one␍\ntwo", displayCommand("one\r\ntwo"))
        assertEquals("␛[2K␀␡", displayCommand("\u001B[2K\u0000\u007F"))
    }

    @Test fun `bidi overrides and isolates are shown as escapes`() {
        for (cp in listOf(0x202A, 0x202B, 0x202C, 0x202D, 0x202E, 0x2066, 0x2067, 0x2068, 0x2069, 0x200E, 0x200F, 0x061C)) {
            val shown = displayCommand("ls A" + String(Character.toChars(cp)) + "B")
            assertEquals("U+%04X".format(cp), "ls A⟨U+%04X⟩B".format(cp), shown)
        }
    }

    @Test fun `zero-width and other format characters are shown as escapes`() {
        for (cp in listOf(0x200B, 0x200C, 0x200D, 0x2060, 0xFEFF, 0x00AD, 0x2061, 0xE0001)) {
            assertEquals("U+%04X".format(cp), "x⟨U+%04X⟩y".format(cp), displayCommand("x" + String(Character.toChars(cp)) + "y"))
        }
    }

    @Test fun `disguising spaces separators controls private use and lone surrogates are escaped`() {
        assertEquals("a⟨U+00A0⟩b", displayCommand("a b"))
        assertEquals("a⟨U+3000⟩b", displayCommand("a　b"))
        assertEquals("a⟨U+2028⟩b⟨U+2029⟩c", displayCommand("a b c"))
        assertEquals("⟨U+0085⟩", displayCommand("\u0085"))
        assertEquals("⟨U+E000⟩", displayCommand(""))
        assertEquals("⟨U+D800⟩", displayCommand("\uD800"))
        assertEquals("⟨U+0378⟩", displayCommand("͸"))
    }

    @Test fun `a valid surrogate pair is one character not two escapes`() {
        assertEquals("🌍", displayCommand("🌍"))
    }

    @Test fun `the escaped text contains no hidden character and no tab or carriage return`() {
        val hidden = (0 until 0x110000).filter { cp ->
            val t = Character.getType(cp).toByte()
            cp != '\n'.code && cp != ' '.code && (t == Character.CONTROL || t == Character.FORMAT || t == Character.LINE_SEPARATOR ||
                t == Character.PARAGRAPH_SEPARATOR || t == Character.SPACE_SEPARATOR || t == Character.PRIVATE_USE || t == Character.UNASSIGNED)
        }
        for (cp in hidden.filter { it < 0x3100 || it > 0xDFFF }.take(3000)) {
            val shown = displayCommand(String(Character.toChars(cp)))
            assertTrue("U+%04X must be escaped, was %s".format(cp, shown), shown.length > 1 || shown[0] in "␉␍␛␀␡")
        }
    }

    @Test fun `the line count is the original lines and ignores one final break`() {
        assertEquals(0, commandLineCount("")); assertEquals(1, commandLineCount("uptime")); assertEquals(1, commandLineCount("uptime\n"))
        assertEquals(16, commandLineCount("uptime" + "\n".repeat(15) + "curl x|sh")); assertEquals(3, commandLineCount("a\n\nb"))
    }
}
