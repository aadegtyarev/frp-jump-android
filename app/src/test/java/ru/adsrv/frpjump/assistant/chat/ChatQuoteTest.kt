package ru.adsrv.frpjump.assistant.chat

import org.junit.Assert.*
import org.junit.Test

class ChatQuoteTest {
    private val note = "[start truncated]"

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

    @Test fun `a long selection keeps its end and the note comes first, before the block`() {
        val lines = (1..2_000).joinToString("\n") { "line $it" }
        val quote = ChatQuote.block(lines, note)!!
        assertTrue(quote.length <= ChatQuote.LIMIT)
        assertTrue(quote.length > ChatQuote.LIMIT - 40)
        assertTrue(quote.startsWith("$note\n```\nline "))
        assertTrue(quote.endsWith("line 2000\n```\n"))
        assertFalse(quote.contains("line 1\n"))
        // Cut on a line boundary: the first kept line is whole.
        val first = quote.removePrefix("$note\n```\n").substringBefore('\n')
        assertTrue(first, Regex("line \\d+").matches(first))
        assertTrue(lines.endsWith(quote.removePrefix("$note\n```\n").removeSuffix("\n```\n")))
    }

    @Test fun `one very long line is cut by characters from its start, the end kept`() {
        val text = "a".repeat(10_000) + "END"
        val quote = ChatQuote.block(text, note)!!
        assertTrue(quote.length <= ChatQuote.LIMIT)
        assertTrue(quote.length > ChatQuote.LIMIT - 10)
        assertTrue(quote.startsWith("$note\n```\naaa"))
        assertTrue(quote.endsWith("aEND\n```\n"))
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
        assertTrue(quote.startsWith("$note\n"))
    }

    @Test fun `the threshold by lines, exactly at it and one over`() {
        // "a\n" * k plus a last "a": k+1 lines, 2k+1 characters; the quote adds 9 (fences and newlines).
        val atLimit = "a\n".repeat((ChatQuote.LIMIT - 10) / 2) + "a"
        assertEquals(ChatQuote.LIMIT - 9, atLimit.length)
        assertEquals("```\n$atLimit\n```\n", ChatQuote.block(atLimit, note))
        val over = "a\n" + atLimit
        val cut = ChatQuote.block(over, note)!!
        assertTrue(cut.startsWith("$note\n```\n"))
        assertTrue(cut.length <= ChatQuote.LIMIT)
        assertTrue(cut.endsWith("a\n```\n"))
    }

    @Test fun `a cut never splits a surrogate pair`() {
        val pair = "\uD83D\uDE00"
        // Every alignment of the pairs at the cut leaves only whole pairs (one line, so cut by characters).
        for (offset in 0..3) {
            val quote = ChatQuote.block("a".repeat(offset) + pair.repeat(5_000), note)!!
            assertTrue(quote.length <= ChatQuote.LIMIT)
            assertTrue(quote.startsWith(note))
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
