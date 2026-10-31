package ru.adsrv.frpjump.files.text

/**
 * JavaScript, wb-rules among it: comments (`//`, and `/* */` across lines),
 * strings (`'`, `"`, and template literals across lines), keywords,
 * `true` / `false` / `null` / `undefined`, numbers, and regular
 * expression literals as strings where a value is expected (after `(`,
 * `=`, `,`, `return`…), so a quote or backtick inside one opens nothing.
 * A template literal left open does not go on past a blank line: one that
 * was not meant to be open stops colouring the file there. A scan without
 * patterns.
 */
object JsHighlighter : Highlighter {
    const val BLOCK_COMMENT = 1
    const val TEMPLATE = 2

    private val KEYWORDS = setOf(
        "var", "let", "const", "function", "return", "if", "else", "for", "while", "do", "switch", "case", "default",
        "break", "continue", "new", "delete", "typeof", "instanceof", "in", "of", "try", "catch", "finally", "throw",
        "class", "extends", "super", "this", "import", "export", "from", "async", "await", "yield", "void", "with",
        "debugger", "static",
    )
    private val LITERALS = setOf("true", "false", "null", "undefined", "NaN", "Infinity")

    override fun line(text: String, state: Int, out: Spans): Int {
        val n = text.length
        var i = 0
        when (state) {
            BLOCK_COMMENT -> {
                val close = text.indexOf("*/")
                if (close < 0) return BLOCK_COMMENT.also { out.add(0, n, Token.COMMENT) }
                out.add(0, close + 2, Token.COMMENT)
                i = close + 2
            }
            TEMPLATE -> {
                if (text.isBlank()) return 0
                val end = templateEnd(text, 0)
                if (end < 0) return TEMPLATE.also { out.add(0, n, Token.STRING) }
                out.add(0, end, Token.STRING)
                i = end
            }
        }
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    out.add(i, n, Token.COMMENT)
                    return 0
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val close = text.indexOf("*/", i + 2)
                    if (close < 0) return BLOCK_COMMENT.also { out.add(i, n, Token.COMMENT) }
                    out.add(i, close + 2, Token.COMMENT)
                    i = close + 2
                }
                c == '/' && regexAllowed(text, i) -> {
                    val end = regexEnd(text, i)
                    if (end < 0) {
                        i++
                    } else {
                        out.add(i, end, Token.STRING)
                        i = end
                    }
                }
                c == '"' || c == '\'' -> {
                    val end = quotedEnd(text, i, c)
                    out.add(i, end, Token.STRING)
                    i = end
                }
                c == '`' -> {
                    val end = templateEnd(text, i + 1)
                    if (end < 0) return TEMPLATE.also { out.add(i, n, Token.STRING) }
                    out.add(i, end, Token.STRING)
                    i = end
                }
                c.isDigit() && (i == 0 || !isWordChar(text[i - 1])) -> {
                    val end = numberEnd(text, i)
                    out.add(i, end, Token.NUMBER)
                    i = end
                }
                c.isLetter() || c == '_' || c == '$' -> {
                    var end = i + 1
                    while (end < n && (isWordChar(text[end]) || text[end] == '$')) end++
                    // A property (`x.default`, `{ in: 1 }` aside) is not a keyword.
                    val property = i > 0 && text[i - 1] == '.'
                    val word = text.substring(i, end)
                    when {
                        property -> Unit
                        word in KEYWORDS -> out.add(i, end, Token.KEYWORD)
                        word in LITERALS -> out.add(i, end, Token.LITERAL)
                    }
                    i = end
                }
                else -> i++
            }
        }
        return 0
    }

    /** Whether a `/` at [at] starts a regular expression: what comes before it wants a value, not a division. */
    private fun regexAllowed(text: String, at: Int): Boolean {
        var k = at - 1
        while (k >= 0 && text[k].isWhitespace()) k--
        if (k < 0) return true
        val c = text[k]
        if (c in "(,=:[!&|?{};+-*%<>~^") return true
        if (!isWordChar(c)) return false
        var w = k
        while (w > 0 && isWordChar(text[w - 1])) w--
        return text.substring(w, k + 1) in REGEX_AFTER
    }

    /** Past a regular expression literal's flags, its `/` at [start]; -1 when it does not close on this line. */
    private fun regexEnd(text: String, start: Int): Int {
        var i = start + 1
        var inClass = false
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i++
                '[' -> inClass = true
                ']' -> inClass = false
                '/' -> if (!inClass) {
                    if (i == start + 1) return -1
                    var end = i + 1
                    while (end < text.length && text[end].isLetter()) end++
                    return end
                }
            }
            i++
        }
        return -1
    }

    private val REGEX_AFTER = setOf("return", "typeof", "case", "do", "else", "in", "of", "new", "delete", "void", "throw", "yield", "await")

    /** Past the closing backtick of a template literal whose text starts at [from]; -1 when it goes on to the next line. */
    private fun templateEnd(text: String, from: Int): Int {
        var i = from
        while (i < text.length) {
            when (text[i]) {
                '\\' -> i += 2
                '`' -> return i + 1
                else -> i++
            }
        }
        return -1
    }
}
