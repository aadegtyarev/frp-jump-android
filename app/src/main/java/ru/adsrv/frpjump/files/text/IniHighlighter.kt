package ru.adsrv.frpjump.files.text

/**
 * INI, conf, properties, systemd units, `.env`: `#` and `;` comment lines,
 * `[sections]`, the key of `key = value` (or of `key value`, as in
 * `mosquitto.conf`), `export` of `.env`; a quoted value as a string, a
 * number or a yes/no word as such. Line by line, no state.
 */
object IniHighlighter : Highlighter {
    private val BOOLEANS = setOf("true", "false", "yes", "no", "on", "off")
    private val NUMBER = Regex("""[-+]?\d+(\.\d+)?[a-zA-Z%]{0,3}""")

    override fun line(text: String, state: Int, out: Spans): Int {
        if (text.length > MAX_PATTERN_LINE) return 0
        val start = text.indexOfFirst { !it.isWhitespace() }
        if (start < 0) return 0
        val first = text[start]
        if (first == '#' || first == ';' || first == '!') return 0.also { out.add(start, text.length, Token.COMMENT) }
        val trimmedEnd = text.trimEnd().length
        if (first == '[') {
            val close = text.indexOf(']', start)
            if (close > start) {
                out.add(start, close + 1, Token.SECTION)
                return 0
            }
        }
        var keyStart = start
        if (text.startsWith("export ", start)) {
            out.add(start, start + 6, Token.KEYWORD)
            keyStart = text.indexOfFirst(start + 7) { !it.isWhitespace() }.takeIf { it >= 0 } ?: return 0
        }
        val eq = text.indexOfAny(charArrayOf('=', ':'), keyStart)
        val space = text.indexOfFirst(keyStart) { it.isWhitespace() }
        val keyEnd = when {
            eq > keyStart && (space < 0 || space > eq || text.substring(space, eq).isBlank()) -> eq
            space > keyStart -> space
            else -> return 0
        }
        val key = text.substring(keyStart, keyEnd).trimEnd()
        if (key.isEmpty() || key.any { it == '"' || it == '\'' }) return 0
        out.add(keyStart, keyStart + key.length, Token.KEY)
        val valueStart = text.indexOfFirst(keyEnd + if (keyEnd == eq) 1 else 0) { !it.isWhitespace() }
        if (valueStart < 0 || valueStart >= trimmedEnd) return 0
        val value = text.substring(valueStart, trimmedEnd)
        when {
            value.length >= 2 && (value[0] == '"' || value[0] == '\'') && value.last() == value[0] -> out.add(valueStart, trimmedEnd, Token.STRING)
            value.lowercase() in BOOLEANS -> out.add(valueStart, trimmedEnd, Token.LITERAL)
            NUMBER.matches(value) -> out.add(valueStart, trimmedEnd, Token.NUMBER)
        }
        return 0
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (i in from until length) if (predicate(this[i])) return i
        return -1
    }
}
