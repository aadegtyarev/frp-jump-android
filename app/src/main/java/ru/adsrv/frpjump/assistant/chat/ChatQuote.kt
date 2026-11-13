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
     * one without visible text. A selection that does not fit [limit] is cut (never between the halves of a surrogate pair) and [note]
     * says so on its own line after the block. The fence is longer than any run of backticks inside, so the text cannot close it.
     */
    fun block(selection: String?, note: String, limit: Int = LIMIT): String? {
        val text = selection?.trimStart('\n', '\r')?.trimEnd()?.takeIf { it.isNotBlank() } ?: return null
        var keep = minOf(text.length, limit)
        while (true) {
            val cut = cutAt(text, keep)
            val body = text.substring(0, cut).trimEnd()
            val fence = "`".repeat(maxOf(3, longestRun(body) + 1))
            val quote = fence + "\n" + body + "\n" + fence + "\n" + if (cut < text.length) note + "\n" else ""
            if (quote.length <= limit || keep <= 0) return quote
            keep -= quote.length - limit
        }
    }

    /** [draft] with [quote] after it, separated by a blank line; the draft is otherwise kept as it is. */
    fun appended(draft: String, quote: String): String = when {
        draft.isEmpty() -> quote
        draft.endsWith("\n\n") -> draft + quote
        draft.endsWith("\n") -> draft + "\n" + quote
        else -> draft + "\n\n" + quote
    }

    private fun cutAt(text: String, keep: Int): Int =
        if (keep in 1 until text.length && Character.isHighSurrogate(text[keep - 1]) && Character.isLowSurrogate(text[keep])) keep - 1 else keep.coerceAtLeast(0)

    private fun longestRun(text: String): Int {
        var best = 0
        var run = 0
        for (ch in text) { run = if (ch == '`') run + 1 else 0; if (run > best) best = run }
        return best
    }
}
