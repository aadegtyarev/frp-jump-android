package ru.adsrv.frpjump.files.text

/**
 * JavaScript, wb-rules among it: comments (`//`, and `/* */` across lines),
 * strings (`'`, `"`, and template literals across lines), keywords,
 * `true` / `false` / `null` / `undefined`, numbers. Regular expression
 * literals are not told from division: a quote inside one may colour the
 * rest of that line as a string. A scan without patterns.
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
