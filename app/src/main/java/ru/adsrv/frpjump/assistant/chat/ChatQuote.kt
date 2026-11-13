package ru.adsrv.frpjump.assistant.chat

/**
 * A terminal selection quoted into the chat draft (Terminal › ⋮ › Ask the assistant). The quote is the owner's own draft text, not an
 * instruction of the app: it goes through the same draft as typing and is sent only when the owner sends it.
 */
object ChatQuote {
    /** The whole quote, fences and note included, is at most this many characters. */
    const val LIMIT = 4_000

    /**
     * [selection] as a fenced code block ending in a newline, so the cursor placed after it starts a new line. Null for no selection or
     * one without visible text. A selection that does not fit [limit] keeps its END (in terminal output the latest lines matter most):
     * [note] ("start truncated") is the first line of the quote, before the block, so the draft shows what is missing. The cut is on a
     * line boundary; a last line that alone is too long is cut by characters, never between the halves of a surrogate pair. The fence
     * is longer than any run of backticks inside, so the text cannot close it.
     */
    fun block(selection: String?, note: String, limit: Int = LIMIT): String? {
        val text = selection?.trimStart('\n', '\r')?.trimEnd()?.takeIf { it.isNotBlank() } ?: return null
        render(text, null).let { if (it.length <= limit) return it }
        var keep = minOf(text.length, limit)
        while (true) {
            val body = tail(text, keep)
            val quote = render(body, note)
            if (quote.length <= limit || keep <= 0) return quote
            keep -= maxOf(1, quote.length - limit)
        }
    }

    private fun render(body: String, note: String?): String {
        val fence = "`".repeat(maxOf(3, longestRun(body) + 1))
        return (if (note != null) note + "\n" else "") + fence + "\n" + body + "\n" + fence + "\n"
    }

    /** At most [keep] characters from the end of [text], starting at a line start when one lies within them, else at a whole character. */
    private fun tail(text: String, keep: Int): String {
        if (keep <= 0) return ""
        var start = text.length - keep
        if (start <= 0) return text
        val lineStart = if (text[start - 1] == '\n') start else text.indexOf('\n', start).let { if (it < 0) -1 else it + 1 }
        start = when {
            lineStart in 1 until text.length -> lineStart
            Character.isLowSurrogate(text[start]) && Character.isHighSurrogate(text[start - 1]) -> start + 1
            else -> start
        }
        return text.substring(start).trimStart('\n', '\r')
    }

    /** [draft] with [quote] after it, separated by a blank line; the draft is otherwise kept as it is. */
    fun appended(draft: String, quote: String): String = when {
        draft.isEmpty() -> quote
        draft.endsWith("\n\n") -> draft + quote
        draft.endsWith("\n") -> draft + "\n" + quote
        else -> draft + "\n\n" + quote
    }

    private fun longestRun(text: String): Int {
        var best = 0
        var run = 0
        for (ch in text) { run = if (ch == '`') run + 1 else 0; if (run > best) best = run }
        return best
    }
}
