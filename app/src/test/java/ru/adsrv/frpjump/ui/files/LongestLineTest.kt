package ru.adsrv.frpjump.ui.files

import org.junit.Assert.assertEquals
import org.junit.Test

/** The viewer leaves text unwrapped when a line is too long for Sora to wrap in time. */
class LongestLineTest {
    @Test
    fun `the longest line, without its break`() {
        assertEquals(0, longestLine(""))
        assertEquals(3, longestLine("abc"))
        assertEquals(5, longestLine("ab\nabcde\nx\n"))
        assertEquals(4, longestLine("ab\r\nabc\r\n"))
    }

    @Test
    fun `a 2 MB single line is over the wrapping limit`() {
        assert(longestLine("1,".repeat(1_000_000)) > MAX_WRAP_LINE)
    }
}
