package ru.adsrv.frpjump.mqtt

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * How a level reads in the path bar and the rows: as is, an empty one as
 * [emptyLabel] (`(empty)`).
 */
fun levelLabel(level: String, emptyLabel: String): String = level.ifEmpty { emptyLabel }

/** The path bar's part for the empty first level, the branch of the topics that start with `/`. */
const val LEADING_SLASH = "/"

/**
 * How a node's name reads in a row and on its card: the empty first level
 * (topics starting with `/`) as [leadingLabel] (`/ (leading slash)`), any
 * other level as [levelLabel] does.
 */
fun nodeLabel(path: MqttPath, emptyLabel: String, leadingLabel: String): String =
    if (path.levels.size == 1 && path.name.isEmpty()) leadingLabel else levelLabel(path.name, emptyLabel)

/**
 * The path bar's parts for [path]: [rootLabel] (`#`, the whole tree) for
 * the root, then one per level: the empty first level as `/` (so
 * `/devices/x` reads `# /devices / x`), another empty level as
 * [emptyLabel]. Part `i` opens the path of the first `i` levels
 * ([pathAtPart]); [pathBarSeparator] says where a `/` goes between parts.
 */
fun pathBarLabels(path: MqttPath, rootLabel: String, emptyLabel: String): List<String> =
    listOf(rootLabel) + path.levels.mapIndexed { i, level -> if (i == 0 && level.isEmpty()) LEADING_SLASH else levelLabel(level, emptyLabel) }

/** Whether a `/` is drawn before part [index] of [pathBarLabels]: between levels, not after the root or the leading `/`. */
fun pathBarSeparator(path: MqttPath, index: Int): Boolean = index > 1 && !(index == 2 && path.levels.firstOrNull() == "")

/** The place part [index] of [pathBarLabels] stands for: the root for 0, else the first [index] levels. */
fun pathAtPart(path: MqttPath, index: Int): MqttPath = MqttPath(path.levels.take(index))

/**
 * A place as a topic text to read: the topic as is (`/devices/x`), the
 * empty first level alone (the branch of the topics starting with `/`) as
 * `/`; the root, the whole tree, has none and gives null.
 */
fun shownPath(path: MqttPath): String? = when {
    path.isRoot -> null
    path.levels == listOf("") -> LEADING_SLASH
    else -> path.format()
}

/**
 * A search hit's path relative to the level searched, as its real text
 * (`/devices/x/controls/K1` at the root); a single empty level (the topic
 * `<level>/`) as [emptyLabel].
 */
fun hitLabel(relative: MqttPath, emptyLabel: String): String = relative.format().ifEmpty { emptyLabel }

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
 * characters (bidi overrides and isolates, zero-width space, BOM), which
 * could reorder or hide what is shown, are dropped, except the joiners
 * ZWNJ and ZWJ, which only shape text; runs of spaces become
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
            Character.FORMAT -> if (cp in JOINERS) {
                if (space && out.isNotEmpty()) out.append(' ')
                space = false
                out.appendCodePoint(cp)
            }
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
            Character.getType(cp).toByte() == Character.FORMAT && cp !in JOINERS -> Unit
            Character.getType(cp).toByte() in BREAKS -> out.append(' ')
            else -> out.appendCodePoint(cp)
        }
    }
    return if (i < text.length) out.toString().trimEnd() + "…" else out.toString()
}

/** ZWNJ and ZWJ: format characters that shape text (Persian, Indic scripts, emoji sequences); kept. */
private val JOINERS = setOf(0x200C, 0x200D)

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

/** Why a typed topic cannot be used: the contract's checks before a topic goes to the broker. */
enum class TopicProblem {
    EMPTY,

    /** `+` or `#`: a topic names one place, not a filter. */
    WILDCARD,

    /** The NUL character, which MQTT forbids in a topic. */
    NUL,

    /** More than [MAX_TOPIC_BYTES] bytes in UTF-8. */
    TOO_LONG,
}

/** The most bytes an MQTT topic (or filter) holds in UTF-8. */
const val MAX_TOPIC_BYTES = 65_535

/**
 * What stops [text], taken as is (no trimming), from being a topic; null
 * when it is one. [suffix] is what will be added before it goes out (`/#`
 * for a pin), counted in the length.
 */
fun topicProblem(text: String, suffix: String = ""): TopicProblem? = when {
    text.isEmpty() -> TopicProblem.EMPTY
    text.any { it == '+' || it == '#' } -> TopicProblem.WILDCARD
    text.contains('\u0000') -> TopicProblem.NUL
    (text + suffix).toByteArray(Charsets.UTF_8).size > MAX_TOPIC_BYTES -> TopicProblem.TOO_LONG
    else -> null
}

/** Where search looks: the open [level] and below, or the whole tree (the root) once widened ([whole]). */
fun searchScope(level: MqttPath, whole: Boolean): MqttPath = if (whole) MqttPath.ROOT else level
