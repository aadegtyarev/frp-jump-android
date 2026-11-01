package ru.adsrv.frpjump.mqtt

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** How a topic card shows a value (see the contract's "A topic"). */
enum class PayloadView { TEXT, JSON, HEX }

/** A value read once for the card: the view it opens in and, for JSON, its lines. */
data class PayloadReading(val view: PayloadView, val json: List<JsonLine>?)

/**
 * Reads [payload] once (off the main thread for a large one): not UTF-8 →
 * [PayloadView.HEX]; UTF-8 that parses as a JSON object or array, at most
 * [MAX_JSON_BYTES] and [MAX_JSON_DEPTH] deep → [PayloadView.JSON] with its
 * lines; else [PayloadView.TEXT].
 */
fun readPayload(payload: ByteArray): PayloadReading {
    val text = decodeUtf8(payload) ?: return PayloadReading(PayloadView.HEX, null)
    val tree = parseJsonTree(payload.size, text) ?: return PayloadReading(PayloadView.TEXT, null)
    return PayloadReading(PayloadView.JSON, jsonLines(tree))
}

/** The view [payload] opens in; see [readPayload]. */
fun detectView(payload: ByteArray): PayloadView = readPayload(payload).view

/** [payload] as a JSON object or array, or null: not UTF-8, not JSON, a scalar, too large or too deep. */
fun jsonTree(payload: ByteArray): JsonElement? = decodeUtf8(payload)?.let { parseJsonTree(payload.size, it) }

private fun parseJsonTree(size: Int, text: String): JsonElement? {
    if (size > MAX_JSON_BYTES) return null
    val start = text.firstOrNull { !it.isWhitespace() }
    if (start != '{' && start != '[') return null
    // Checked on the text first (cheap), then on the tree: the text count can be
    // fooled (the parser takes unquoted words, `a"` among them), and the lines are built recursively.
    if (nestingDepth(text) > MAX_JSON_DEPTH) return null
    return try {
        Json.parseToJsonElement(text).takeIf { (it is JsonObject || it is JsonArray) && jsonDepth(it) <= MAX_JSON_DEPTH }
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}

/**
 * How deep objects and arrays nest in [element] (a scalar: 0), walked
 * without recursion: safe on any tree the parser built. Stops counting
 * past [MAX_JSON_DEPTH] + 1.
 */
internal fun jsonDepth(element: JsonElement): Int {
    val stack = ArrayDeque<Pair<JsonElement, Int>>()
    stack.addLast(element to 1)
    var max = 0
    while (stack.isNotEmpty()) {
        val (e, depth) = stack.removeLast()
        val children = when (e) {
            is JsonObject -> e.values
            is JsonArray -> e
            else -> continue
        }
        max = maxOf(max, depth)
        if (max > MAX_JSON_DEPTH) return max
        children.forEach { stack.addLast(it to depth + 1) }
    }
    return max
}

/** How deep `{` and `[` nest in [text], outside strings; malformed text is counted as it comes. */
internal fun nestingDepth(text: String): Int {
    var depth = 0
    var max = 0
    var inString = false
    var escaped = false
    for (c in text) {
        when {
            inString -> when {
                escaped -> escaped = false
                c == '\\' -> escaped = true
                c == '"' -> inString = false
            }
            c == '"' -> inString = true
            c == '{' || c == '[' -> max = maxOf(max, ++depth)
            c == '}' || c == ']' -> depth--
        }
    }
    return max
}

/**
 * One line of pretty-printed JSON. A line that opens a non-empty object or
 * array can be folded: [end] is the index of its closing line, and
 * [children] how many entries it holds; -1 for any other line.
 */
data class JsonLine(val depth: Int, val text: String, val end: Int = -1, val children: Int = 0) {
    val foldable: Boolean get() = end >= 0
}

/** [element] pretty-printed as lines, two spaces a level when drawn; keys and strings in JSON escaping. */
fun jsonLines(element: JsonElement): List<JsonLine> {
    val lines = mutableListOf<JsonLine>()
    fun add(depth: Int, key: String?, value: JsonElement, comma: Boolean) {
        val head = key?.let { JsonPrimitive(it).toString() + ": " }.orEmpty()
        val tail = if (comma) "," else ""
        val entries: List<Pair<String?, JsonElement>> = when (value) {
            is JsonObject -> value.entries.map { it.key to it.value }
            is JsonArray -> value.map { null to it }
            else -> {
                lines.add(JsonLine(depth, head + value.toString() + tail))
                return
            }
        }
        val (open, close) = if (value is JsonObject) "{" to "}" else "[" to "]"
        if (entries.isEmpty()) {
            lines += JsonLine(depth, "$head$open$close$tail")
            return
        }
        val at = lines.size
        lines += JsonLine(depth, head + open)
        entries.forEachIndexed { i, (k, v) -> add(depth + 1, k, v, i < entries.lastIndex) }
        lines += JsonLine(depth, close + tail)
        lines[at] = lines[at].copy(end = lines.lastIndex, children = entries.size)
    }
    add(0, null, element, comma = false)
    return lines
}

/**
 * The lines shown with the opening lines at [folded] indexes folded: a
 * folded line stands for everything up to its closing line, which it
 * replaces (the closing line's text is kept for its comma). Each result is
 * the index of the line in [lines] and whether it is shown folded.
 */
fun visibleJsonLines(lines: List<JsonLine>, folded: Set<Int>): List<Pair<Int, Boolean>> {
    val out = mutableListOf<Pair<Int, Boolean>>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        if (line.foldable && i in folded) {
            out += i to true
            i = line.end + 1
        } else {
            out += i to false
            i++
        }
    }
    return out
}

/** [payload] as hex dump lines: offset, [perLine] bytes in hex, the printable ASCII of them; at most [maxBytes] bytes. */
fun hexDump(payload: ByteArray, perLine: Int = HEX_PER_LINE, maxBytes: Int = payload.size): List<String> {
    val shown = minOf(payload.size, maxBytes)
    return (0 until shown step perLine).map { offset ->
        val end = minOf(offset + perLine, shown)
        val hex = (offset until end).joinToString(" ") { "%02x".format(payload[it].toInt() and 0xff) }
        val ascii = (offset until end).map { payload[it].toInt().let { b -> if (b in 0x20..0x7e) b.toChar() else '.' } }.joinToString("")
        "%04x  %-${perLine * 3 - 1}s  %s".format(offset, hex, ascii)
    }
}

/** What "Copy value" puts on the clipboard, and whether it was cut to [MAX_COPY_CHARS]. */
data class CopiedValue(val text: String, val cut: Boolean)

/**
 * What "Copy value" takes in [view]: Text and JSON copy the payload's text
 * as received (invalid UTF-8 replaced), Hex its bytes as hex pairs; at most
 * [MAX_COPY_CHARS] characters (as the terminal copies), never cut inside a
 * surrogate pair or a hex pair.
 */
fun copiedValue(payload: ByteArray, view: PayloadView): CopiedValue = when (view) {
    PayloadView.HEX -> {
        val bytes = MAX_COPY_CHARS / 3
        CopiedValue(hexPreview(payload, bytes).removeSuffix(" …"), payload.size > bytes)
    }
    PayloadView.TEXT, PayloadView.JSON -> {
        val text = String(payload, Charsets.UTF_8)
        if (text.length <= MAX_COPY_CHARS) {
            CopiedValue(text, false)
        } else {
            var cut = MAX_COPY_CHARS
            if (Character.isHighSurrogate(text[cut - 1])) cut--
            CopiedValue(text.substring(0, cut), true)
        }
    }
}

/** The most a copy puts on the clipboard, as the terminal: more would not fit through Binder. */
const val MAX_COPY_CHARS = 64 * 1024

/** A payload larger than this is not parsed as JSON: shown as text. */
const val MAX_JSON_BYTES = 64 * 1024

/** JSON nested deeper than this is shown as text: the lines are built recursively. */
const val MAX_JSON_DEPTH = 64

/** Bytes per line of the hex view: fits a phone's width in a monospace font. */
const val HEX_PER_LINE = 8
