package ru.adsrv.frpjump.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A paste never runs a line by itself; several lines without bracketed paste are asked about. */
class PastePlanTest {
    @Test
    fun dropsAllTrailingBreaksAndBlankLines() {
        assertEquals("ls -l", dropTrailingBreaks("ls -l\n"))
        assertEquals("ls -l", dropTrailingBreaks("ls -l\r\n"))
        assertEquals("ls -l", dropTrailingBreaks("ls -l\r"))
        assertEquals("ls -l", dropTrailingBreaks("ls -l\n\n\r\n"))
        assertEquals("ls -l", dropTrailingBreaks("ls -l\n  \n\t\n "))
        assertEquals("ls -l  ", dropTrailingBreaks("ls -l  \n"))
        assertEquals("a\n\nb", dropTrailingBreaks("a\n\nb\n"))
        assertEquals("ls -l", dropTrailingBreaks("ls -l"))
    }

    @Test
    fun countsLinesOfAnyBreak() {
        assertEquals(1, pasteLines("a"))
        assertEquals(3, pasteLines("a\nb\r\nc"))
        assertEquals(2, pasteLines("a\rb"))
    }

    @Test
    fun joinsTrimmedNonBlankLinesWithASpace() {
        assertEquals("cd /tmp && ls", joinLines("  cd /tmp &&\n\n  ls  \r\n"))
        assertEquals("a b c", joinLines("a\rb\r\nc"))
    }

    @Test
    fun oneLinePastesWithoutItsBreak() {
        assertEquals(PastePlan.Paste("uptime"), planPaste("uptime\n", bracketed = false))
        assertEquals(PastePlan.Paste("uptime"), planPaste("uptime\n", bracketed = true))
    }

    @Test
    fun severalLinesAskWithoutBracketedPaste() {
        assertEquals(PastePlan.Ask("a\nb", 2), planPaste("a\nb\n", bracketed = false))
        assertEquals(PastePlan.Paste("a"), planPaste("a\n\n", bracketed = false))
        assertEquals(PastePlan.Ask("a\n\nb", 3), planPaste("a\n\nb\n \n", bracketed = false))
    }

    @Test
    fun severalLinesPasteAsIsWithBracketedPaste() {
        assertEquals(PastePlan.Paste("a\nb"), planPaste("a\nb\n", bracketed = true))
    }

    @Test
    fun nothingToPaste() {
        assertNull(planPaste("", bracketed = false))
        assertNull(planPaste("\n", bracketed = true))
        assertNull(planPaste("\n \n", bracketed = false))
    }
}
