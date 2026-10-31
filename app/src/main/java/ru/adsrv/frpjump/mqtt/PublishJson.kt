package ru.adsrv.frpjump.mqtt

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Why a payload in the publish sheet's JSON mode cannot be sent as JSON. */
sealed interface JsonProblem {
    data object Empty : JsonProblem

    /** Over [MAX_JSON_BYTES] in UTF-8: not checked; sent as Text instead. */
    data object TooLarge : JsonProblem

    /** Nested deeper than [MAX_JSON_DEPTH]. */
    data object TooDeep : JsonProblem

    /** Not JSON; [line] and [column] (from 1) of where the parser stopped, when it said. */
    data class Invalid(val line: Int?, val column: Int?) : JsonProblem
}

/** A payload checked as JSON: its [element] when valid, else the [problem]. */
data class JsonCheck(val element: JsonElement?, val problem: JsonProblem?)

/**
 * Checks [text] as one JSON value (an object, an array or a scalar, as
 * RFC 8259 allows): not empty, at most [MAX_JSON_BYTES] in UTF-8 and
 * [MAX_JSON_DEPTH] deep (counted on the text before parsing, so deep input
 * cannot exhaust the stack), then parsed.
 */
fun checkJson(text: String): JsonCheck {
    if (text.isBlank()) return JsonCheck(null, JsonProblem.Empty)
    if (utf8Length(text) > MAX_JSON_BYTES) return JsonCheck(null, JsonProblem.TooLarge)
    if (nestingDepth(text) > MAX_JSON_DEPTH) return JsonCheck(null, JsonProblem.TooDeep)
    return try {
        val element = Json.parseToJsonElement(text)
        // The parser takes an unquoted word as a value ("on", [on]); JSON does not.
        if (strictJson(element)) JsonCheck(element, null) else JsonCheck(null, JsonProblem.Invalid(null, null))
    } catch (e: SerializationException) {
        JsonCheck(null, invalidAt(text, e.message))
    } catch (e: IllegalArgumentException) {
        JsonCheck(null, invalidAt(text, e.message))
    }
}

/** Whether every unquoted value in [element] is a JSON number, `true`, `false` or `null`. Depth is capped before parsing. */
private fun strictJson(element: JsonElement): Boolean = when (element) {
    is JsonObject -> element.values.all(::strictJson)
    is JsonArray -> element.all(::strictJson)
    is JsonPrimitive -> element.isString || element is JsonNull || element.content in LITERALS || NUMBER.matches(element.content)
}

private val LITERALS = setOf("true", "false")

private val NUMBER = Regex("""-?(0|[1-9][0-9]*)(\.[0-9]+)?([eE][+-]?[0-9]+)?""")

/** [element] pretty-printed as the card's JSON view draws it: two spaces a level. */
fun formatJson(element: JsonElement): String = jsonLines(element).joinToString("\n") { "  ".repeat(it.depth) + it.text }

/** The parser's "at offset N" in [message] as a line and column of [text]; none when it did not say. */
private fun invalidAt(text: String, message: String?): JsonProblem.Invalid {
    val offset = message?.let { OFFSET.find(it) }?.groupValues?.get(1)?.toIntOrNull()?.coerceIn(0, text.length)
        ?: return JsonProblem.Invalid(null, null)
    val before = text.substring(0, offset)
    val line = before.count { it == '\n' } + 1
    val column = offset - (before.lastIndexOf('\n') + 1) + 1
    return JsonProblem.Invalid(line, column)
}

private val OFFSET = Regex("""at offset (\d+)""")

private fun utf8Length(text: String): Int {
    // Counted without encoding: a payload can be large.
    var bytes = 0
    var i = 0
    while (i < text.length) {
        val c = text[i]
        bytes += when {
            c.code < 0x80 -> 1
            c.code < 0x800 -> 2
            Character.isHighSurrogate(c) && i + 1 < text.length && Character.isLowSurrogate(text[i + 1]) -> {
                i++
                4
            }
            else -> 3
        }
        i++
    }
    return bytes
}
