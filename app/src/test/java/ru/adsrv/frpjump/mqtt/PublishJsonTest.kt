package ru.adsrv.frpjump.mqtt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PublishJsonTest {
    @Test
    fun `any JSON value is valid, a scalar too`() {
        listOf("""{"a":1}""", "[1, 2]", "21.5", "true", "null", "\"on\"", "  {}\n").forEach {
            val check = checkJson(it)
            assertNull(it, check.problem)
            assertNotNull(it, check.element)
        }
    }

    @Test
    fun `an empty value is not JSON`() {
        assertEquals(JsonProblem.Empty, checkJson("").problem)
        assertEquals(JsonProblem.Empty, checkJson(" \n").problem)
    }

    @Test
    fun `broken JSON says where the parser stopped`() {
        listOf("{a:1}", "[1,]", "{\"a\":", "on", "[on]", "{\"a\": off}", "01", "1.", "+1", "NaN", "1 2", "{\"a\":1}}").forEach {
            assertEquals(it, JsonProblem.Invalid::class, checkJson(it).problem!!::class)
        }
        val problem = checkJson("{\n  \"a\": 1,\n  \"b\" 2\n}").problem as JsonProblem.Invalid
        assertEquals(3, problem.line)
        assertNotNull(problem.column)
    }

    @Test
    fun `deep nesting is refused before parsing, without a stack overflow`() {
        val deep = "[".repeat(20_000) + "]".repeat(20_000)
        assertEquals(JsonProblem.TooDeep, checkJson(deep).problem)
        // Unclosed too: counted as it comes.
        assertEquals(JsonProblem.TooDeep, checkJson("{\"a\":".repeat(MAX_JSON_DEPTH + 1)).problem)
        val atLimit = "[".repeat(MAX_JSON_DEPTH) + "]".repeat(MAX_JSON_DEPTH)
        assertNull(checkJson(atLimit).problem)
        // Brackets inside strings do not count.
        assertNull(checkJson("\"" + "[".repeat(1000) + "\"").problem)
    }

    @Test
    fun `a value over the parse limit is not checked`() {
        val big = "[" + "1,".repeat(MAX_JSON_BYTES / 2) + "1]"
        assertEquals(JsonProblem.TooLarge, checkJson(big).problem)
        // Counted in UTF-8 bytes, not characters.
        val wide = "\"" + "я".repeat(MAX_JSON_BYTES / 2) + "\""
        assertEquals(JsonProblem.TooLarge, checkJson(wide).problem)
    }

    @Test
    fun `format pretty-prints as the card's JSON view, keeping numbers as written`() {
        val element = checkJson("""{"a":[1,2.50],"b":{},"c":"x\"y","d":1e3}""").element!!
        val expected = """
            {
              "a": [
                1,
                2.50
              ],
              "b": {},
              "c": "x\"y",
              "d": 1e3
            }
        """.trimIndent()
        assertEquals(expected, formatJson(element))
        assertEquals("21.5", formatJson(checkJson("21.5").element!!))
        // Formatting formatted JSON changes nothing.
        assertEquals(expected, formatJson(checkJson(expected).element!!))
    }
}
