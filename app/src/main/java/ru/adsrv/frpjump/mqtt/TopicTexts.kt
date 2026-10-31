package ru.adsrv.frpjump.mqtt

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * How a level reads in the path bar and the rows: as is, an empty one as
 * [emptyLabel] (`(empty)`).
 */
fun levelLabel(level: String, emptyLabel: String): String = level.ifEmpty { emptyLabel }

/**
 * The path bar's parts for [path]: [rootLabel] (`/`) for the root, then
 * one per level, an empty level as [emptyLabel]. Part `i` opens the path of
 * the first `i` levels ([pathAtPart]).
 */
fun pathBarLabels(path: MqttPath, rootLabel: String, emptyLabel: String): List<String> =
    listOf(rootLabel) + path.levels.map { levelLabel(it, emptyLabel) }

/** The place part [index] of [pathBarLabels] stands for: the root for 0, else the first [index] levels. */
fun pathAtPart(path: MqttPath, index: Int): MqttPath = MqttPath(path.levels.take(index))

/** How a payload reads in one line of a row. */
sealed interface ValueLine {
    /** UTF-8 text made safe for one line ([oneLineText]). */
    data class Text(val text: String) : ValueLine

    /** Not UTF-8: its first bytes in hex ([hexPreview]). */
    data class Hex(val text: String) : ValueLine
}

/** [payload] as one line of at most [maxChars] characters: text when it is UTF-8, else hex. */
fun valueLine(payload: ByteArray, maxChars: Int = ROW_VALUE_CHARS): ValueLine =
    decodeUtf8(payload)?.let { ValueLine.Text(oneLineText(it, maxChars)) } ?: ValueLine.Hex(hexPreview(payload, maxChars / 3))

/** [payload] as text when it is valid UTF-8, else null. */
fun decodeUtf8(payload: ByteArray): String? = try {
    Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(payload))
        .toString()
} catch (_: CharacterCodingException) {
    null
}

/**
 * [text] safe to show in one line: control characters (line breaks, tabs,
 * escapes) and line or paragraph separators become a space; format
 * characters (bidi overrides and isolates, zero-width marks, BOM), which
 * could reorder or hide what is shown, are dropped; runs of spaces become
 * one; ends trimmed; cut to [maxChars] with "…", never inside a surrogate
 * pair.
 */
fun oneLineText(text: String, maxChars: Int = ROW_VALUE_CHARS): String {
    val out = StringBuilder(minOf(text.length, maxChars + 1))
    var space = false
    var i = 0
    while (i < text.length && out.length <= maxChars) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        when (Character.getType(cp).toByte()) {
            Character.FORMAT -> continue
            Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> space = true
            else -> {
                if (Character.isWhitespace(cp)) {
                    space = true
                    continue
                }
                if (space && out.isNotEmpty()) out.append(' ')
                space = false
                out.appendCodePoint(cp)
            }
        }
    }
    if (out.length <= maxChars && i >= text.length) return out.toString()
    var cut = minOf(out.length, maxChars)
    if (cut > 0 && Character.isHighSurrogate(out[cut - 1])) cut--
    return out.substring(0, cut).trimEnd() + "…"
}

/**
 * [text] safe to show over several lines: as [oneLineText] but line
 * breaks and tabs stay; cut to [maxChars] with "…".
 */
fun multiLineText(text: String, maxChars: Int): String {
    val out = StringBuilder(minOf(text.length, maxChars))
    var i = 0
    while (i < text.length && out.length < maxChars) {
        val cp = text.codePointAt(i)
        i += Character.charCount(cp)
        when {
            cp == '\n'.code || cp == '\t'.code -> out.appendCodePoint(cp)
            cp == '\r'.code -> if (i >= text.length || text[i] != '\n') out.append('\n')
            Character.getType(cp).toByte() == Character.FORMAT -> Unit
            Character.getType(cp).toByte() in BREAKS -> out.append(' ')
            else -> out.appendCodePoint(cp)
        }
    }
    return if (i < text.length) out.toString().trimEnd() + "…" else out.toString()
}

private val BREAKS = setOf(Character.CONTROL, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR)

/** The first [maxBytes] bytes of [payload] as hex pairs, "…" when there are more. */
fun hexPreview(payload: ByteArray, maxBytes: Int): String {
    val shown = payload.take(maxBytes).joinToString(" ") { "%02x".format(it.toInt() and 0xff) }
    return if (payload.size > maxBytes) "$shown …" else shown
}

/**
 * Whether a pin covers [path] itself as its topic or branch: a pin made on
 * it with any mask (`<t>`, `<t>/+`, `<t>/#`).
 */
fun pinnedAt(path: MqttPath, pins: Collection<String>): Boolean {
    if (path.isRoot) return false
    val t = path.format()
    return pins.any { it == t || it == "$t/+" || it == "$t/#" }
}

/** Characters of a value a row shows at most. */
const val ROW_VALUE_CHARS = 120
