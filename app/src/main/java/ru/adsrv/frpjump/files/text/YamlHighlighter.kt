package ru.adsrv.frpjump.files.text

/**
 * YAML: keys, `#` comments, quoted strings, `&anchors`, `*aliases` and
 * `!tags`, list dashes, `---`, numbers and `true` / `false` / `null`; a
 * block scalar (`key: |` or `>`) colours the more indented lines under it
 * as a string. The state is 0, or the key's column + 1 while a block
 * scalar goes on.
 */
object YamlHighlighter : Highlighter {
    private val LITERALS = setOf("true", "false", "null", "~", "yes", "no", "on", "off", "True", "False", "Null", "TRUE", "FALSE", "NULL")
    private val NUMBER = Regex("""[-+]?(\d[\d_]*(\.\d*)?([eE][-+]?\d+)?|\.\d+|0x[0-9a-fA-F]+|\.inf|\.nan)""")
    private val BLOCK = Regex("""[|>][-+0-9]*\s*(#.*)?""")

    override fun line(text: String, state: Int, out: Spans): Int {
        if (text.length > MAX_PATTERN_LINE) return state
        val indent = text.indexOfFirst { it != ' ' }.let { if (it < 0) text.length else it }
        if (state > 0) {
            // Blank lines and lines indented past the key belong to the block.
            if (indent == text.length || indent > state - 1) return state.also { out.add(indent, text.length, Token.STRING) }
        }
        if (indent == text.length) return 0
        if (text.startsWith("---") || text.startsWith("...")) {
            out.add(0, 3, Token.PUNCT)
            return 0
        }
        if (text[indent] == '#') return 0.also { out.add(indent, text.length, Token.COMMENT) }
        var i = indent
        // List dashes, `- - x` too.
        while (i < text.length && text[i] == '-' && (i + 1 == text.length || text[i + 1] == ' ')) {
            out.add(i, i + 1, Token.PUNCT)
            i = skipSpaces(text, i + 1)
        }
        val keyColumn = i
        val colon = keyEnd(text, i)
        if (colon > i) {
            out.add(i, colon, Token.KEY)
            out.add(colon, colon + 1, Token.PUNCT)
            i = skipSpaces(text, colon + 1)
        }
        if (colon > keyColumn && i < text.length && BLOCK.matches(text.substring(i))) {
            out.add(i, i + 1, Token.PUNCT)
            val hash = text.indexOf('#', i)
            if (hash > 0) out.add(hash, text.length, Token.COMMENT)
            return keyColumn + 1
        }
        value(text, i, out)
        return 0
    }

    /** Where the key starting at [start] ends (at its `:`), or -1 when the line holds no key there. */
    private fun keyEnd(text: String, start: Int): Int {
        if (start >= text.length) return -1
        val first = text[start]
        if (first == '"' || first == '\'') {
            val end = quotedEnd(text, start, first, escapes = first == '"')
            var j = end
            while (j < text.length && text[j] == ' ') j++
            return if (j < text.length && text[j] == ':' && (j + 1 == text.length || text[j + 1] == ' ')) j else -1
        }
        if (first in "{[&*!|>#%@`") return -1
        var j = start
        while (j < text.length) {
            val c = text[j]
            if (c == '#' && j > start && text[j - 1] == ' ') return -1
            if (c == ':' && (j + 1 == text.length || text[j + 1] == ' ')) return text.substring(start, j).trimEnd().length + start
            j++
        }
        return -1
    }

    /** A value from [start]: strings, anchors, a trailing comment, flow punctuation, a whole scalar as a number or literal. */
    private fun value(text: String, start: Int, out: Spans) {
        var i = start
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '#' && (i == 0 || text[i - 1] == ' ') -> {
                    out.add(i, n, Token.COMMENT)
                    return
                }
                (c == '"' || c == '\'') && (i == start || text[i - 1] in " [{,") -> {
                    val end = quotedEnd(text, i, c, escapes = c == '"')
                    out.add(i, end, Token.STRING)
                    i = end
                }
                (c == '&' || c == '*' || c == '!') && (i == start || text[i - 1] in " [{,") -> {
                    var end = i + 1
                    while (end < n && !text[end].isWhitespace() && text[end] !in ",]}") end++
                    out.add(i, end, Token.ANCHOR)
                    i = end
                }
                c in "{}[]," -> {
                    out.add(i, i + 1, Token.PUNCT)
                    i++
                }
                c == ' ' -> i++
                else -> {
                    // A plain scalar runs to a comment, or within flow to `,` `]` `}`.
                    var end = i
                    while (end < n && !(text[end] == '#' && text[end - 1] == ' ') && text[end] !in ",]}") end++
                    val word = text.substring(i, end).trimEnd()
                    if (word in LITERALS) out.add(i, i + word.length, Token.LITERAL) else if (NUMBER.matches(word)) out.add(i, i + word.length, Token.NUMBER)
                    i = end
                }
            }
        }
    }

    private fun skipSpaces(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i] == ' ') i++
        return i
    }
}
