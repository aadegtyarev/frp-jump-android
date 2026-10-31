package ru.adsrv.frpjump.files.text

/**
 * Shell scripts: `#` comments, `'…'` and `"…"` strings (across lines too),
 * `$name`, `${…}` and `$1` variables (inside `"…"` as well), `NAME=` of an
 * assignment, keywords as whole words. A here-document (`<<EOF`,
 * `<<-'END'`) shows plain up to its closing word, without carrying any
 * quote into it: its state holds the word's hash.
 */
object ShellHighlighter : Highlighter {
    const val IN_DOUBLE = 1
    const val IN_SINGLE = 2
    private const val HEREDOC = 1 shl 30
    private const val HEREDOC_TABS = 1 shl 29
    private const val HASH_BITS = (1 shl 29) - 1

    private val KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in", "function",
        "select", "return", "local", "export", "readonly", "declare", "unset", "shift", "exit", "break", "continue",
        "source", "alias", "set", "trap", "eval", "exec",
    )

    override fun line(text: String, state: Int, out: Spans): Int {
        if (state and HEREDOC != 0) {
            // `<<-` lets the closing word be indented with tabs.
            val shown = if (state and HEREDOC_TABS != 0) text.trimStart('\t') else text
            return if (shown.hashCode() and HASH_BITS == state and HASH_BITS) 0 else state
        }
        val heredoc = intArrayOf(0)
        val after = scan(text, state, out, heredoc)
        // A here-document begun on this line starts on the next, whatever this line left open.
        return if (heredoc[0] != 0) heredoc[0] else after
    }

    private fun scan(text: String, state: Int, out: Spans, heredoc: IntArray): Int {
        val n = text.length
        var i = 0
        when (state) {
            IN_SINGLE -> {
                val close = text.indexOf('\'')
                if (close < 0) return IN_SINGLE.also { out.add(0, n, Token.STRING) }
                out.add(0, close + 1, Token.STRING)
                i = close + 1
            }
            IN_DOUBLE -> {
                i = doubleQuoted(text, 0, out)
                if (i < 0) return IN_DOUBLE
            }
        }
        while (i < n) {
            val c = text[i]
            when {
                c == '\\' -> i += 2
                c == '#' && (i == 0 || text[i - 1].isWhitespace() || text[i - 1] == ';') -> {
                    out.add(i, n, Token.COMMENT)
                    return 0
                }
                c == '<' && text.startsWith("<<", i) -> i = hereDocument(text, i, heredoc)
                c == '\'' -> {
                    val close = text.indexOf('\'', i + 1)
                    if (close < 0) return IN_SINGLE.also { out.add(i, n, Token.STRING) }
                    out.add(i, close + 1, Token.STRING)
                    i = close + 1
                }
                c == '"' -> {
                    i = doubleQuoted(text, i + 1, out, opened = i)
                    if (i < 0) return IN_DOUBLE
                }
                c == '$' -> i = variable(text, i, out)
                c.isLetter() || c == '_' -> {
                    var end = i + 1
                    while (end < n && isWordChar(text[end])) end++
                    val standalone = (i == 0 || text[i - 1].let { it.isWhitespace() || it == ';' || it == '(' || it == '`' || it == '|' || it == '&' }) &&
                        (end == n || text[end].let { it.isWhitespace() || it == ';' || it == ')' })
                    when {
                        end < n && text[end] == '=' && (i == 0 || !isWordChar(text[i - 1]) && text[i - 1] != '-') -> out.add(i, end, Token.VARIABLE)
                        standalone && text.substring(i, end) in KEYWORDS -> out.add(i, end, Token.KEYWORD)
                    }
                    // A word goes on with `-`, `.`, `/`: `fi.conf`, `--done` are not keywords.
                    while (end < n && (isWordChar(text[end]) || text[end] == '-' || text[end] == '.' || text[end] == '/')) end++
                    i = end
                }
                else -> i++
            }
        }
        return 0
    }

    /**
     * `<<WORD`, `<<-WORD`, `<<'WORD'`, `<<"WORD"` at [at]: the state of its
     * body into [heredoc] (the first one on a line counts); `<<<` (a here
     * string) is passed over. Returns where the scan goes on.
     */
    private fun hereDocument(text: String, at: Int, heredoc: IntArray): Int {
        var j = at + 2
        if (j < text.length && text[j] == '<') return j + 1
        val tabs = j < text.length && text[j] == '-'
        if (tabs) j++
        while (j < text.length && (text[j] == ' ' || text[j] == '\t')) j++
        val quote = text.getOrNull(j)?.takeIf { it == '\'' || it == '"' }
        if (quote != null) j++
        val start = j
        while (j < text.length && (isWordChar(text[j]) || text[j] == '-' || text[j] == '.')) j++
        // `$(( a << 2 ))` shifts: a here-document's word starts with a letter, unless quoted.
        if (j == start || (quote == null && !(text[start].isLetter() || text[start] == '_'))) return at + 2
        val word = text.substring(start, j)
        if (quote != null && text.getOrNull(j) == quote) j++
        if (heredoc[0] == 0) heredoc[0] = HEREDOC or (if (tabs) HEREDOC_TABS else 0) or (word.hashCode() and HASH_BITS)
        return j
    }

    /**
     * A double-quoted string whose text starts at [from] ([opened]: its
     * quote): the string coloured, its variables over it. Returns where it
     * ends, or -1 when it goes on to the next line.
     */
    private fun doubleQuoted(text: String, from: Int, out: Spans, opened: Int = 0): Int {
        var i = from
        var pieceStart = opened
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '"' -> {
                    out.add(pieceStart, i + 1, Token.STRING)
                    return i + 1
                }
                '$' -> {
                    out.add(pieceStart, i, Token.STRING)
                    val end = variable(text, i, out)
                    pieceStart = end
                    i = end
                }
                else -> i++
            }
        }
        out.add(pieceStart, text.length, Token.STRING)
        return -1
    }

    /** `$name`, `${…}`, `$1`, `$?` and the like at [start]: coloured; returns where it ends (a lone `$` is passed over). */
    private fun variable(text: String, start: Int, out: Spans): Int {
        val n = text.length
        if (start + 1 >= n) return n
        val next = text[start + 1]
        val end = when {
            next == '{' -> text.indexOf('}', start + 2).let { if (it < 0) n else it + 1 }
            next.isLetter() || next == '_' -> {
                var e = start + 2
                while (e < n && isWordChar(text[e])) e++
                e
            }
            next.isDigit() || next in "@#?*!$-" -> start + 2
            else -> return start + 1
        }
        out.add(start, end, Token.VARIABLE)
        return end
    }
}
