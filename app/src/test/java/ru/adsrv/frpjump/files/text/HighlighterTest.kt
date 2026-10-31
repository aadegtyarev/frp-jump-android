package ru.adsrv.frpjump.files.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each format's colouring on typical lines, the state carried from line to line included. */
class HighlighterTest {
    /** [lines] through [h] in order: each line's coloured pieces as `text to token`. */
    private fun run(h: Highlighter, vararg lines: String): List<List<Pair<String, Token>>> {
        var state = 0
        return lines.map { line ->
            val out = Spans()
            state = h.line(line, state, out)
            spanList(out.toArray()).map { (s, e, t) -> line.substring(s, e) to t }
        }
    }

    private fun one(h: Highlighter, line: String) = run(h, line).single()

    @Test
    fun json() {
        assertEquals(
            listOf("{" to Token.PUNCT, "\"name\"" to Token.KEY, ":" to Token.PUNCT, "\"wb \\\"7\\\"\"" to Token.STRING, "," to Token.PUNCT,
                "\"n\"" to Token.KEY, ":" to Token.PUNCT, "-1.5e+3" to Token.NUMBER, "," to Token.PUNCT,
                "\"ok\"" to Token.KEY, ":" to Token.PUNCT, "true" to Token.LITERAL, "," to Token.PUNCT,
                "\"x\"" to Token.KEY, ":" to Token.PUNCT, "null" to Token.LITERAL, "}" to Token.PUNCT),
            one(JsonHighlighter, """{"name": "wb \"7\"", "n": -1.5e+3, "ok": true, "x": null}"""),
        )
        // A run of closing punctuation is one piece.
        assertEquals(listOf("1" to Token.NUMBER, "]}]," to Token.PUNCT), one(JsonHighlighter, "1]}],"))
    }

    @Test
    fun `json on one long line stays linear`() {
        val line = "[" + (1..20_000).joinToString(",") { """{"k":$it}""" } + "]"
        val pieces = one(JsonHighlighter, line)
        assertEquals(20_000, pieces.count { it.second == Token.KEY })
        assertEquals(20_000, pieces.count { it.second == Token.NUMBER })
    }

    @Test
    fun javascript() {
        assertEquals(
            listOf("var" to Token.KEYWORD, "1" to Token.NUMBER, "\"a//b\"" to Token.STRING, "// note" to Token.COMMENT),
            one(JsHighlighter, """var x = 1 + "a//b"; // note"""),
        )
        // wb-rules: a property named like a keyword is not one.
        assertEquals(listOf("true" to Token.LITERAL), one(JsHighlighter, "dev.x.default = true"))
    }

    @Test
    fun `javascript block comments and templates across lines`() {
        val lines = run(JsHighlighter, "a = 1; /* one", "two", "end */ return `x", "y`; b")
        assertEquals(listOf("1" to Token.NUMBER, "/* one" to Token.COMMENT), lines[0])
        assertEquals(listOf("two" to Token.COMMENT), lines[1])
        assertEquals(listOf("end */" to Token.COMMENT, "return" to Token.KEYWORD, "`x" to Token.STRING), lines[2])
        assertEquals(listOf("y`" to Token.STRING), lines[3])
    }

    @Test
    fun shell() {
        assertEquals(
            listOf("if" to Token.KEYWORD, "\"" to Token.STRING, "\$HOME" to Token.VARIABLE, "/x\"" to Token.STRING, "then" to Token.KEYWORD, "# check" to Token.COMMENT),
            one(ShellHighlighter, """if [ -d "${'$'}HOME/x" ]; then # check"""),
        )
        assertEquals(
            listOf("NAME" to Token.VARIABLE, "'a b'" to Token.STRING, "\${NAME}" to Token.VARIABLE, "\$1" to Token.VARIABLE),
            one(ShellHighlighter, "NAME='a b'; echo \${NAME} \$1 fi.conf --done"),
        )
        // `#` inside a word is not a comment.
        assertEquals(emptyList<Pair<String, Token>>(), one(ShellHighlighter, "echo a#b"))
    }

    @Test
    fun `shell strings across lines`() {
        val lines = run(ShellHighlighter, "echo \"one", "\$two", "three\" done", "echo 'a", "b' x")
        assertEquals(listOf("\"one" to Token.STRING), lines[0])
        assertEquals(listOf("\$two" to Token.VARIABLE), lines[1])
        assertEquals(listOf("three\"" to Token.STRING, "done" to Token.KEYWORD), lines[2])
        assertEquals(listOf("'a" to Token.STRING), lines[3])
        assertEquals(listOf("b'" to Token.STRING), lines[4])
    }

    @Test
    fun ini() {
        assertEquals(listOf("[Service]" to Token.SECTION), one(IniHighlighter, "[Service]"))
        assertEquals(listOf("ExecStart" to Token.KEY), one(IniHighlighter, "ExecStart=/usr/bin/x --a=1"))
        assertEquals(listOf("Restart" to Token.KEY, "on" to Token.LITERAL), one(IniHighlighter, "Restart = on"))
        assertEquals(listOf("# comment" to Token.COMMENT), one(IniHighlighter, "# comment"))
        assertEquals(listOf("; comment" to Token.COMMENT), one(IniHighlighter, "  ; comment"))
        assertEquals(listOf("export" to Token.KEYWORD, "TOKEN" to Token.KEY, "\"a b\"" to Token.STRING), one(IniHighlighter, "export TOKEN=\"a b\""))
        // mosquitto.conf: `key value`.
        assertEquals(listOf("listener" to Token.KEY, "1883" to Token.NUMBER), one(IniHighlighter, "listener 1883"))
        assertEquals(listOf("port" to Token.KEY, "8080" to Token.NUMBER), one(IniHighlighter, "port: 8080"))
    }

    @Test
    fun yaml() {
        assertEquals(
            listOf("-" to Token.PUNCT, "name" to Token.KEY, ":" to Token.PUNCT, "\"wb\"" to Token.STRING, "# who" to Token.COMMENT),
            one(YamlHighlighter, "  - name: \"wb\" # who"),
        )
        assertEquals(listOf("base" to Token.KEY, ":" to Token.PUNCT, "&base" to Token.ANCHOR), one(YamlHighlighter, "base: &base"))
        assertEquals(listOf("<<" to Token.KEY, ":" to Token.PUNCT, "*base" to Token.ANCHOR), one(YamlHighlighter, "<<: *base"))
        assertEquals(listOf("port" to Token.KEY, ":" to Token.PUNCT, "1883" to Token.NUMBER), one(YamlHighlighter, "port: 1883"))
        assertEquals(listOf("on" to Token.KEY, ":" to Token.PUNCT, "true" to Token.LITERAL), one(YamlHighlighter, "on: true"))
        assertEquals(listOf("url" to Token.KEY, ":" to Token.PUNCT), one(YamlHighlighter, "url: http://x:80/a"))
        assertEquals(listOf("# all" to Token.COMMENT), one(YamlHighlighter, "# all"))
        assertEquals(listOf("---" to Token.PUNCT), one(YamlHighlighter, "---"))
    }

    @Test
    fun `yaml block scalars run while indented past their key`() {
        val lines = run(YamlHighlighter, "  script: |", "    echo: no key", "", "    done", "  next: 1")
        assertEquals(listOf("script" to Token.KEY, ":" to Token.PUNCT, "|" to Token.PUNCT), lines[0])
        assertEquals(listOf("echo: no key" to Token.STRING), lines[1])
        assertEquals(emptyList<Pair<String, Token>>(), lines[2])
        assertEquals(listOf("done" to Token.STRING), lines[3])
        assertEquals(listOf("next" to Token.KEY, ":" to Token.PUNCT, "1" to Token.NUMBER), lines[4])
    }

    @Test
    fun markdown() {
        assertEquals(listOf("## Setup" to Token.HEADING), one(MarkdownHighlighter, "## Setup"))
        assertEquals(
            listOf("-" to Token.MARKER, "**bold**" to Token.STRONG, "*it*" to Token.EMPHASIS, "`code`" to Token.CODE, "[wiki](https://wb.ru/x)" to Token.LINK),
            one(MarkdownHighlighter, "- **bold** and *it* with `code`, see [wiki](https://wb.ru/x)"),
        )
        assertEquals(listOf(">" to Token.MARKER, "1." to Token.MARKER, "https://a.b/c" to Token.LINK), one(MarkdownHighlighter, "> 1. go https://a.b/c"))
        assertEquals(listOf("- [x]" to Token.MARKER), one(MarkdownHighlighter, "- [x] done"))
        assertEquals(listOf("---" to Token.MARKER), one(MarkdownHighlighter, "---"))
        // An underscore inside a word is not emphasis.
        assertEquals(emptyList<Pair<String, Token>>(), one(MarkdownHighlighter, "snake_case_name"))
    }

    @Test
    fun `markdown fences run until closed`() {
        val lines = run(MarkdownHighlighter, "```sh", "# not a heading", "```", "# Heading", "~~~", "```", "~~~", "*x*")
        assertEquals(listOf("```sh" to Token.CODE), lines[0])
        assertEquals(listOf("# not a heading" to Token.CODE), lines[1])
        assertEquals(listOf("```" to Token.CODE), lines[2])
        assertEquals(listOf("# Heading" to Token.HEADING), lines[3])
        // A backtick line does not close a tilde fence.
        assertEquals(listOf("```" to Token.CODE), lines[5])
        assertEquals(listOf("~~~" to Token.CODE), lines[6])
        assertEquals(listOf("*x*" to Token.EMPHASIS), lines[7])
    }

    @Test
    fun logs() {
        assertEquals(
            listOf("2026-10-31T12:00:01.123Z" to Token.TIME, "ERROR" to Token.LEVEL_ERROR, "10.0.0.5:1883" to Token.NUMBER),
            one(LogHighlighter, "2026-10-31T12:00:01.123Z ERROR cannot reach 10.0.0.5:1883"),
        )
        assertEquals(
            listOf("Oct 31 12:00:01" to Token.TIME, "warning" to Token.LEVEL_WARN),
            one(LogHighlighter, "Oct 31 12:00:01 wb7 wb-mqtt-serial[312]: warning: slow"),
        )
        assertEquals(listOf("[   12.345678]" to Token.TIME, "Failed" to Token.LEVEL_ERROR), one(LogHighlighter, "[   12.345678] Failed to load"))
        assertEquals(listOf("INFO" to Token.LEVEL_QUIET, "DEBUG" to Token.LEVEL_QUIET), one(LogHighlighter, "INFO a DEBUG b"))
        // Whole words only: "errors" and "informed" are not levels.
        assertEquals(emptyList<Pair<String, Token>>(), one(LogHighlighter, "no errors, informed"))
    }

    @Test
    fun `pattern highlighters leave a huge line plain and keep the state`() {
        val huge = "# " + "x".repeat(MAX_PATTERN_LINE)
        for (h in listOf(MarkdownHighlighter, LogHighlighter, IniHighlighter, YamlHighlighter)) {
            val out = Spans()
            assertEquals(h.toString(), 0, h.line(huge, 0, out))
            assertEquals(h.toString(), 0, out.size)
        }
        assertEquals(5, YamlHighlighter.line("x".repeat(MAX_PATTERN_LINE + 1), 5, Spans()))
    }

    @Test
    fun `line colours carry the state and finish`() {
        val doc = TextDocument("/* a\nb */ var x\n")
        val colours = LineColours(doc, JsHighlighter)
        assertTrue(colours.advance(1))
        assertEquals(null, colours.of(1))
        assertEquals(false, colours.advance(10))
        assertEquals(listOf(Triple(0, 4, Token.COMMENT), Triple(5, 8, Token.KEYWORD)), spanList(colours.of(1)!!))
    }
}
