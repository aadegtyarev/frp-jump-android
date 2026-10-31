package ru.adsrv.frpjump.files.text

/**
 * JSON, and JSON Lines: keys (a string followed by `:`), strings, numbers,
 * `true` / `false` / `null`, punctuation. A scan without patterns, so a
 * minified file on one long line costs no more than its length. No state:
 * JSON strings do not span lines.
 */
object JsonHighlighter : Highlighter {
    private val LITERALS = setOf("true", "false", "null")

    override fun line(text: String, state: Int, out: Spans): Int {
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '"' -> {
                    val end = quotedEnd(text, i, '"')
                    var j = end
                    while (j < n && text[j].isWhitespace()) j++
                    out.add(i, end, if (j < n && text[j] == ':') Token.KEY else Token.STRING)
                    i = end
                }
                c == '-' || c.isDigit() -> {
                    val end = numberEnd(text, i).coerceAtLeast(i + 1)
                    out.add(i, end, Token.NUMBER)
                    i = end
                }
                c.isLetter() -> {
                    var end = i
                    while (end < n && text[end].isLetter()) end++
                    if (end - i <= 5 && text.substring(i, end) in LITERALS) out.add(i, end, Token.LITERAL)
                    i = end
                }
                c == '{' || c == '}' || c == '[' || c == ']' || c == ':' || c == ',' -> {
                    var end = i + 1
                    // A run of punctuation is one piece: `}]},` costs one span, not four.
                    while (end < n && text[end].let { it == '{' || it == '}' || it == '[' || it == ']' || it == ':' || it == ',' }) end++
                    out.add(i, end, Token.PUNCT)
                    i = end
                }
                else -> i++
            }
        }
        return 0
    }
}
