package ru.adsrv.frpjump.files.text

/** What a coloured piece of a line is; the screen picks its colour from the theme. */
enum class Token {
    KEYWORD,
    STRING,
    NUMBER,

    /** `true`, `false`, `null` and the like. */
    LITERAL,
    COMMENT,

    /** A JSON / YAML / INI key. */
    KEY,
    PUNCT,

    /** `$name` of a shell, `NAME=` of an assignment. */
    VARIABLE,

    /** `[section]`. */
    SECTION,

    /** YAML `&anchor`, `*alias`, `!tag`. */
    ANCHOR,
    HEADING,
    EMPHASIS,
    STRONG,

    /** Inline code and fenced blocks of Markdown. */
    CODE,
    LINK,

    /** List marks, quote marks, rules. */
    MARKER,

    /** A log's time stamp. */
    TIME,

    /** ERROR, FATAL, CRIT. */
    LEVEL_ERROR,

    /** WARN. */
    LEVEL_WARN,

    /** INFO, DEBUG. */
    LEVEL_QUIET,
}

/**
 * The coloured pieces of one line, in order and not overlapping, as
 * `start, end, token ordinal` triples (offsets in the line, end exclusive).
 */
class Spans {
    private var data = IntArray(24)
    var size = 0
        private set

    fun add(start: Int, end: Int, token: Token) {
        if (end <= start) return
        if (size + 3 > data.size) data = data.copyOf(data.size * 2)
        data[size] = start
        data[size + 1] = end
        data[size + 2] = token.ordinal
        size += 3
    }

    fun toArray(): IntArray = data.copyOf(size)

    fun clear() {
        size = 0
    }
}

/** The pieces of [spans] (from [Spans.toArray]) as readable triples, for tests and the screen. */
fun spanList(spans: IntArray): List<Triple<Int, Int, Token>> =
    (spans.indices step 3).map { Triple(spans[it], spans[it + 1], Token.entries[spans[it + 2]]) }

/**
 * Colours a text line by line. [line] gets the state the line before left
 * (0 at the start: nothing open) and returns the state it leaves, so an
 * open fence, block comment, quote or block scalar carries on.
 */
fun interface Highlighter {
    fun line(text: String, state: Int, out: Spans): Int
}

/** Lines longer than this are left plain by the pattern-based highlighters (logs, Markdown, YAML, INI). */
const val MAX_PATTERN_LINE = 4000

/** The highlighter of [format]; plain text has none. */
fun highlighterFor(format: TextFormat): Highlighter? = when (format) {
    TextFormat.PLAIN -> null
    TextFormat.MARKDOWN -> MarkdownHighlighter
    TextFormat.LOG -> LogHighlighter
    TextFormat.JSON -> JsonHighlighter
    TextFormat.YAML -> YamlHighlighter
    TextFormat.INI -> IniHighlighter
    TextFormat.SHELL -> ShellHighlighter
    TextFormat.JS -> JsHighlighter
}

/**
 * [highlighter] over one line, never failing: a line it chokes on (a
 * pattern too deep for the stack) is left plain and the state passes on
 * unchanged.
 */
fun Highlighter.safeLine(text: String, state: Int, out: Spans): Int = try {
    line(text, state, out)
} catch (_: StackOverflowError) {
    out.clear()
    state
} catch (_: RuntimeException) {
    out.clear()
    state
}

internal fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'

/** The end of a quoted string opened by [quote] at [start] (past the closing quote), or the line's end; `\` escapes. */
internal fun quotedEnd(text: String, start: Int, quote: Char, escapes: Boolean = true): Int {
    var i = start + 1
    while (i < text.length) {
        val c = text[i]
        if (escapes && c == '\\') {
            i += 2
            continue
        }
        if (c == quote) return i + 1
        i++
    }
    return text.length
}

/** Where a number starting at [start] ends: decimal, hex, exponent, `_` separators. */
internal fun numberEnd(text: String, start: Int): Int {
    var i = start
    if (i < text.length && (text[i] == '-' || text[i] == '+')) i++
    while (i < text.length) {
        val c = text[i]
        val ok = c.isLetterOrDigit() || c == '.' || c == '_' ||
            ((c == '-' || c == '+') && i > start && (text[i - 1] == 'e' || text[i - 1] == 'E') && !text.startsWith("0x", start, ignoreCase = true))
        if (!ok) break
        i++
    }
    return i
}

/** The index (of a triple, a multiple of 3) of the first piece in [spans] that ends after [offset]; [spans]' size when none does. */
fun firstSpanAfter(spans: IntArray, offset: Int): Int {
    var lo = 0
    var hi = spans.size / 3
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (spans[mid * 3 + 1] <= offset) lo = mid + 1 else hi = mid
    }
    return lo * 3
}
