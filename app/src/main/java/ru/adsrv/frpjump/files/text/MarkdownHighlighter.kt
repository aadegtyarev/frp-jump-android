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
    private val RULE = Regex("""([-*_])(\s*\1){2,}\s*""")
    private val LIST = Regex("""([-*+]|\d{1,9}[.)])(\s+\[[ xX]])?(?=\s|$)""")
    private val INLINE = Regex(
        """(`+)[^`].*?\1""" +
            """|\*\*[^*\s](?:[^*]*[^*\s])?\*\*|__[^_\s](?:[^_]*[^_\s])?__""" +
            """|\*[^*\s](?:[^*]*[^*\s])?\*|(?<![\w])_[^_\s](?:[^_]*[^_\s])?_(?![\w])""" +
            """|!?\[[^\]]*]\([^)\s]*(?:\s+"[^"]*")?\)|<https?://[^>\s]+>|https?://[^\s)>\]]+""",
    )

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
        if (RULE.matches(body)) return 0.also { out.add(indent, text.length, Token.MARKER) }
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
        inline(text, i, out)
        return 0
    }

    private fun inline(text: String, from: Int, out: Spans) {
        for (m in INLINE.findAll(text, from)) {
            val s = m.value
            val token = when {
                s.startsWith("`") -> Token.CODE
                s.startsWith("**") || s.startsWith("__") -> Token.STRONG
                s.startsWith("*") || s.startsWith("_") -> Token.EMPHASIS
                else -> Token.LINK
            }
            out.add(m.range.first, m.range.last + 1, token)
        }
    }
}
