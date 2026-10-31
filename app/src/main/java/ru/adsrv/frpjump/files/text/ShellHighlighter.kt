package ru.adsrv.frpjump.files.text

/**
 * Shell scripts: `#` comments, `'…'` and `"…"` strings (across lines too),
 * `$name`, `${…}` and `$1` variables (inside `"…"` as well), `NAME=` of an
 * assignment, keywords as whole words. Here-documents show plain.
 */
object ShellHighlighter : Highlighter {
    const val IN_DOUBLE = 1
    const val IN_SINGLE = 2

    private val KEYWORDS = setOf(
        "if", "then", "else", "elif", "fi", "for", "while", "until", "do", "done", "case", "esac", "in", "function",
        "select", "return", "local", "export", "readonly", "declare", "unset", "shift", "exit", "break", "continue",
        "source", "alias", "set", "trap", "eval", "exec",
    )

    override fun line(text: String, state: Int, out: Spans): Int {
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
