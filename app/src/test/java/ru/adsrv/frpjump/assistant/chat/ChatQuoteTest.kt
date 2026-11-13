package ru.adsrv.frpjump.assistant.chat

import org.junit.Assert.*
import org.junit.Test

class ChatQuoteTest {
    private val note = "[truncated]"

    @Test fun `a selection becomes a code block ending in a newline`() {
        assertEquals("```\nls -l\ntotal 0\n```\n", ChatQuote.block("ls -l\ntotal 0", note))
    }

    @Test fun `no selection or blank selection gives no quote`() {
        assertNull(ChatQuote.block(null, note))
        assertNull(ChatQuote.block("", note))
        assertNull(ChatQuote.block("  \n \r\n", note))
    }

    @Test fun `indentation is kept, surrounding blank lines are not`() {
        assertEquals("```\n  indented\n```\n", ChatQuote.block("\n\n  indented  \n\n", note))
    }

    @Test fun `a long selection is cut so the whole quote fits the limit and says so`() {
        val quote = ChatQuote.block("x".repeat(10_000), note)!!
        assertTrue(quote.length <= ChatQuote.LIMIT)
        assertTrue(quote.length > ChatQuote.LIMIT - 10)
        assertTrue(quote.endsWith("```\n$note\n"))
        assertTrue(quote.startsWith("```\nxxx"))
    }

    @Test fun `a selection that fits is whole and has no note`() {
        val text = "y".repeat(ChatQuote.LIMIT - 9)
        val quote = ChatQuote.block(text, note)!!
        assertEquals("```\n$text\n```\n", quote)
        assertFalse(quote.contains(note))
    }

    @Test fun `one character over the fitting size is cut with the note`() {
        val quote = ChatQuote.block("y".repeat(ChatQuote.LIMIT - 8), note)!!
        assertTrue(quote.length <= ChatQuote.LIMIT)
        assertTrue(quote.contains(note))
    }

    @Test fun `a cut never splits a surrogate pair`() {
        val pair = "😀"
        // Every alignment of the pairs at the cut leaves only whole pairs.
        for (offset in 0..3) {
            val quote = ChatQuote.block("a".repeat(offset) + pair.repeat(5_000), note)!!
            assertTrue(quote.length <= ChatQuote.LIMIT)
            assertTrue(quote.contains(note))
            quote.forEachIndexed { i, ch ->
                if (Character.isHighSurrogate(ch)) assertTrue("lone high at $i", i + 1 < quote.length && Character.isLowSurrogate(quote[i + 1]))
                if (Character.isLowSurrogate(ch)) assertTrue("lone low at $i", i > 0 && Character.isHighSurrogate(quote[i - 1]))
            }
        }
    }

    @Test fun `a longer fence cannot be closed by backticks in the text`() {
        assertEquals("````\nuse ``` here\n````\n", ChatQuote.block("use ``` here", note))
    }

    @Test fun `a draft is kept and the quote goes after it`() {
        val quote = "```\nx\n```\n"
        assertEquals(quote, ChatQuote.appended("", quote))
        assertEquals("why?\n\n$quote", ChatQuote.appended("why?", quote))
        assertEquals("why?\n\n$quote", ChatQuote.appended("why?\n", quote))
        assertEquals("why?\n\n$quote", ChatQuote.appended("why?\n\n", quote))
    }
}
