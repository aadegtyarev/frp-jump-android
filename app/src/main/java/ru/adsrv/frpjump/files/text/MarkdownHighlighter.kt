package ru.adsrv.frpjump.files.text

/**
 * Markdown: headings, `**strong**`, `*emphasis*` / `_emphasis_`, inline
 * code, fenced code blocks (``` and ~~~, across lines), links and bare
 * URLs, list marks, task boxes, quote marks and rules. The state is 0, or
 * which fence is open.
 */
object MarkdownHighlighter : Highlighter {
    const val IN_BACKTICKS = 1
    const val IN_TILDES = 2

    private val HEADING = Regex("""#{1,6}(\s.*)?""")
    private val LIST = Regex("""([-*+]|\d{1,9}[.)])(\s+\[[ xX]])?(?=\s|$)""")

    override fun line(text: String, state: Int, out: Spans): Int {
        val indent = text.indexOfFirst { it != ' ' && it != '\t' }.let { if (it < 0) text.length else it }
        val body = text.substring(indent)
        if (state != 0) {
            out.add(0, text.length, Token.CODE)
            val fence = if (state == IN_BACKTICKS) "```" else "~~~"
            return if (body.startsWith(fence) && body.trimEnd().all { it == fence[0] }) 0 else state
        }
        when {
            body.startsWith("```") -> return IN_BACKTICKS.also { out.add(0, text.length, Token.CODE) }
            body.startsWith("~~~") -> return IN_TILDES.also { out.add(0, text.length, Token.CODE) }
        }
        if (text.length > MAX_PATTERN_LINE) return 0
        if (HEADING.matches(body)) return 0.also { out.add(indent, text.length, Token.HEADING) }
        if (isRule(body)) return 0.also { out.add(indent, text.length, Token.MARKER) }
        var i = indent
        // Quote marks, nested: `> > text`.
        while (i < text.length && text[i] == '>') {
            out.add(i, i + 1, Token.MARKER)
            i++
            while (i < text.length && text[i] == ' ') i++
        }
        LIST.matchAt(text, i)?.let {
            out.add(i, it.range.last + 1, Token.MARKER)
            i = it.range.last + 1
        }
        InlineScan(text).run(i, out)
        return 0
    }

    /** `---`, `* * *`, `___`: three or more of one mark, spaces between. */
    private fun isRule(body: String): Boolean {
        val mark = body.firstOrNull() ?: return false
        if (mark != '-' && mark != '*' && mark != '_') return false
        var count = 0
        for (c in body) {
            when (c) {
                mark -> count++
                ' ', '\t' -> Unit
                else -> return false
            }
        }
        return count >= 3
    }
}

/**
 * Inline Markdown of one line, in one pass: code spans, strong, emphasis,
 * links, URLs. Each search for a closing mark remembers its answer
 * ([next]), so a line of unclosed marks (`[a](` many times over) costs
 * about its length, not its square.
 */
private class InlineScan(private val text: String) {
    private val memo = HashMap<String, Long>()

    /**
     * The first position at or after [from] where [isAt] holds, for the
     * search named [key]: answered from the last search of that key when it
     * still holds (the scan only moves on).
     */
    private inline fun next(key: String, from: Int, isAt: (Int) -> Boolean): Int {
        memo[key]?.let { m ->
            val was = (m ushr 32).toInt()
            val at = m.toInt()
            if (was <= from && (at == -1 || at >= from)) return at
        }
        var k = from
        while (k < text.length && !isAt(k)) k++
        val at = if (k < text.length) k else -1
        memo[key] = (from.toLong() shl 32) or (at.toLong() and 0xFFFFFFFFL)
        return at
    }

    fun run(from: Int, out: Spans) {
        val n = text.length
        var i = from
        while (i < n) {
            val c = text[i]
            i = when {
                c == '\\' -> i + 2
                c == '`' -> code(i, out)
                c == '*' || c == '_' -> emphasis(i, c, out)
                c == '[' || (c == '!' && i + 1 < n && text[i + 1] == '[') -> link(i, out)
                c == '<' && text.startsWith("<http", i) -> autolink(i, out)
                (c == 'h') && (i == 0 || !isWordChar(text[i - 1])) && (text.startsWith("https://", i) || text.startsWith("http://", i)) -> {
                    var end = i
                    while (end < n && !text[end].isWhitespace() && text[end] != ')' && text[end] != '>' && text[end] != ']') end++
                    out.add(i, end, Token.LINK)
                    end
                }
                else -> i + 1
            }
        }
    }

    private fun runLength(at: Int, c: Char): Int {
        var e = at
        while (e < text.length && text[e] == c) e++
        return e - at
    }

    /** A code span opened by the run of backticks at [i]: closed by the next run of the same length. */
    private fun code(i: Int, out: Spans): Int {
        val r = runLength(i, '`')
        val close = next("`$r", i + r) { k -> text[k] == '`' && text[k - 1] != '`' && runLength(k, '`') == r }
        if (close < 0) return i + r
        out.add(i, close + r, Token.CODE)
        return close + r
    }

    /** `**strong**` / `__strong__` or `*emphasis*` / `_emphasis_` opened at [i]; `_` only at a word's edge. */
    private fun emphasis(i: Int, c: Char, out: Spans): Int {
        val n = text.length
        val r = runLength(i, c)
        if (c == '_' && i > 0 && isWordChar(text[i - 1])) return i + r
        val d = if (r >= 2) 2 else 1
        val first = i + d
        if (first >= n || text[first].isWhitespace()) return i + r
        val close = next("$c$d", first + 1) { k ->
            text[k] == c && !text[k - 1].isWhitespace() && (d == 1 || (k + 1 < n && text[k + 1] == c)) &&
                (c != '_' || k + d >= n || !isWordChar(text[k + d]))
        }
        if (close < 0) return i + r
        out.add(i, close + d, if (d == 2) Token.STRONG else Token.EMPHASIS)
        return close + d
    }

    /** `<https://…>` at [i]: up to `>`, no spaces. */
    private fun autolink(i: Int, out: Spans): Int {
        val close = next(">", i) { text[it] == '>' || text[it].isWhitespace() }
        if (close < 0 || text[close] != '>') return i + 1
        out.add(i, close + 1, Token.LINK)
        return close + 1
    }

    /** `[text](url)` or `![alt](url)` at [i]. */
    private fun link(i: Int, out: Spans): Int {
        val open = if (text[i] == '!') i + 1 else i
        val bracket = next("]", open + 1) { text[it] == ']' }
        if (bracket < 0 || bracket + 1 >= text.length || text[bracket + 1] != '(') return open + 1
        val paren = next(")", bracket + 2) { text[it] == ')' }
        if (paren < 0) return open + 1
        out.add(i, paren + 1, Token.LINK)
        return paren + 1
    }
}
