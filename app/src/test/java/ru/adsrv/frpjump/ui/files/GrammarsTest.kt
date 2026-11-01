package ru.adsrv.frpjump.ui.files

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.eclipse.tm4e.core.TMException
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.internal.oniguruma.impl.joni.JoniOnigRegExp
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.Registry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.adsrv.frpjump.files.text.TextFormat
import java.io.File

/**
 * Every bundled grammar loads in tm4e and colours a line: a grammar the
 * library cannot read (a capture that is not a rule, a regex joni cannot
 * compile) would leave its files plain on the phone.
 */
class GrammarsTest {
    private val dir = File("src/main/assets/textmate")

    private val samples = mapOf(
        TextFormat.MARKDOWN to "# Title with `code` and [link](http://x)\n```json\n{\"a\": 1}\n```",
        TextFormat.LOG to "2026-11-01 05:00:02 ERROR n=7 failed",
        TextFormat.JSON to "{\"a\": [1, true, null, \"s\"]}",
        TextFormat.YAML to "a: &x 1\nlist:\n  - *x # c",
        TextFormat.INI to "[Unit]\nDescription=x ; c",
        TextFormat.SHELL to "for f in \$HOME/*; do echo \"\$f\"; done",
        TextFormat.JS to "defineRule(\"r\", { whenChanged: \"a/b\", then: function (v) { return /x+/.test(v); } });",
        TextFormat.PYTHON to "def f(x):\n    return re.compile(r\"a+\")  # c",
        TextFormat.GO to "func main() { fmt.Println(\"hi\") }",
        TextFormat.C to "#include <stdio.h>\nint main(void) { return 0; }",
        TextFormat.LUA to "local t = { a = 1 } -- c",
        TextFormat.DOCKERFILE to "FROM debian:bookworm\nRUN apt-get update",
        TextFormat.SQL to "SELECT a FROM t WHERE b = 'x';",
        TextFormat.XML to "<?xml version=\"1.0\"?>\n<a b=\"c\"><!-- c --></a>",
        TextFormat.HTML to "<p class=\"x\">hi</p><style>a{color:red}</style><script>let a = 1;</script>",
        TextFormat.CSS to "a:hover { color: #fff; }",
    )

    @Test
    fun `every format's grammar and its includes load and colour a sample`() {
        val registry = Registry()
        // Every bundled file, so includes between them resolve (Markdown's fences, HTML's CSS and JS).
        val files = dir.listFiles { f -> f.name.endsWith(".tmLanguage.json") }!!.sortedBy { it.name }
        for (f in files) registry.addGrammar(IGrammarSource.fromFile(f))
        for (format in TextFormat.entries.filter { it.scope != null }) {
            val grammar = registry.grammarForScopeName(format.scope!!)
            assertNotNull(format.name, grammar)
            var state: IStateStack? = null
            var scoped = 0
            for (line in samples.getValue(format).lines()) {
                val result = grammar!!.tokenizeLine(line, state, null)
                state = result.ruleStack
                scoped += result.tokens.count { t -> t.scopes.size > 1 }
            }
            assertTrue("${format.name}: no scoped tokens", scoped > 0)
        }
    }

    @Test
    fun `a sample for every format but plain`() {
        assertEquals(TextFormat.entries.filter { it.scope != null }.toSet(), samples.keys)
    }

    @Test
    fun `every pattern of every bundled grammar compiles in joni`() {
        val failures = mutableListOf<String>()
        for (f in dir.listFiles { x -> x.name.endsWith(".tmLanguage.json") }!!.sortedBy { it.name }) {
            patterns(Json.parseToJsonElement(f.readText())).forEach { (key, pattern) ->
                // An end or while pattern with back-references is compiled once they are filled in.
                if ((key == "end" || key == "while") && BACKREF.containsMatchIn(pattern)) return@forEach
                try {
                    // joni, as on the phone: the app does not bundle Sora's optional native Oniguruma.
                    JoniOnigRegExp(pattern)
                } catch (e: TMException) {
                    failures += "${f.name}: $pattern (${e.cause?.message ?: e.message})"
                }
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    /** The regex strings (key and pattern) under `match`, `begin`, `end` and `while`, anywhere in [e]. */
    private fun patterns(e: JsonElement): List<Pair<String, String>> = when (e) {
        is JsonObject -> e.flatMap { (k, v) ->
            if (k in REGEX_KEYS && v is JsonPrimitive && v.isString) listOf(k to v.content) else patterns(v)
        }
        is JsonArray -> e.flatMap { patterns(it) }
        else -> emptyList()
    }

    private companion object {
        val REGEX_KEYS = setOf("match", "begin", "end", "while")
        val BACKREF = Regex("""\\\d""")
    }
}
