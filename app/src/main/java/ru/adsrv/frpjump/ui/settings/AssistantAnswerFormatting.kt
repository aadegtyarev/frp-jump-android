package ru.adsrv.frpjump.ui.settings

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

private val answerList = Regex("^( {0,3})([-+*])[ \\t]+(.*)$")
private val htmlTag = Regex("^<(/?)([A-Za-z][A-Za-z0-9-]*)(?:\\s[^<>]*)?(/?)>$")

/** Display-only, deliberately small Markdown subset. Never interprets links or HTML. */
internal fun formatAssistantAnswer(source: String): AnnotatedString {
    val result = AnnotatedString.Builder()
    var position = 0
    while (position < source.length) {
        val end = source.indexOf('\n', position).let { if (it < 0) source.length else it }
        val line = source.substring(position, end)
        if (line.startsWith("```") && !line.drop(3).contains('`')) {
            var scan = if (end < source.length) end + 1 else end
            var closing = -1
            var closingEnd = scan
            while (scan < source.length) {
                val next = source.indexOf('\n', scan).let { if (it < 0) source.length else it }
                if (source.substring(scan, next).trim() == "```") {
                    closing = scan; closingEnd = next; break
                }
                scan = if (next < source.length) next + 1 else next
            }
            if (closing < 0) { result.append(source.substring(position)); break }
            val start = result.length
            result.append(source.substring(end + 1, closing))
            if (result.length > start) result.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, result.length)
            if (closingEnd < source.length && (closing == end + 1 || source[closing - 1] != '\n')) result.append('\n')
            position = if (closingEnd < source.length) closingEnd + 1 else closingEnd
            continue
        }
        val list = answerList.matchEntire(line)
        if (list != null) {
            result.append(list.groupValues[1]); result.append("• ")
            appendInline(result, list.groupValues[3])
        } else {
            // Ordered markers are already readable list numbering, not formatting delimiters.
            appendInline(result, line)
        }
        if (end < source.length) result.append('\n')
        position = if (end < source.length) end + 1 else end
    }
    return result.toAnnotatedString()
}

private data class AnswerMarker(val offset: Int, val length: Int, val style: SpanStyle)

private fun appendInline(out: AnnotatedString.Builder, line: String) {
    val removed = BooleanArray(line.length)
    val protected = BooleanArray(line.length)
    val styles = mutableListOf<Pair<AnswerMarker, AnswerMarker>>()
    // Precompute closing positions so malformed repeated openers remain linear-time.
    val nextAngle = IntArray(line.length + 1) { -1 }
    val nextLink = IntArray(line.length + 1) { -1 }
    for (p in line.indices.reversed()) {
        nextAngle[p] = if (line[p] == '>') p else nextAngle[p + 1]
        nextLink[p] = if (line[p] == ']' && p + 1 < line.length && line[p + 1] == '(') p else nextLink[p + 1]
    }
    // Match simple HTML tag pairs only to keep the entire unsupported construct
    // literal. No tag is evaluated, loaded or made interactive.
    val htmlEnd = IntArray(line.length) { -1 }
    val tags = mutableMapOf<String, ArrayDeque<Int>>()
    var tagPosition = 0
    while (tagPosition < line.length) {
        if (line[tagPosition] != '<') { tagPosition++; continue }
        val end = nextAngle[tagPosition + 1]
        if (end < 0) break
        val tag = htmlTag.matchEntire(line.substring(tagPosition, end + 1))
        if (tag != null) {
            val name = tag.groupValues[2].lowercase()
            if (tag.groupValues[1] == "/") {
                val opening = tags[name]?.removeLastOrNull()
                if (opening != null) htmlEnd[opening] = end
            } else if (tag.groupValues[3] != "/") {
                tags.getOrPut(name) { ArrayDeque() }.addLast(tagPosition)
            }
        }
        tagPosition = end + 1
    }
    tags.values.forEach { openings -> openings.forEach { htmlEnd[it] = line.lastIndex } }
    val escaped = BooleanArray(line.length)
    var i = 0
    while (i < line.length) {
        if (line[i] == '\\' && i + 1 < line.length) { escaped[i + 1] = true; i += 2 } else i++
    }
    val matchingParen = IntArray(line.length) { -1 }
    val parens = ArrayDeque<Int>()
    for (p in line.indices) if (!escaped[p]) {
        if (line[p] == '(') parens.addLast(p)
        if (line[p] == ')' && parens.isNotEmpty()) matchingParen[parens.removeLast()] = p
    }
    val tickRun = IntArray(line.length)
    i = 0
    while (i < line.length) {
        if (line[i] != '`' || escaped[i]) { i++; continue }
        var end = i + 1
        while (end < line.length && line[end] == '`' && !escaped[end]) end++
        tickRun[i] = end - i
        i = end
    }
    val nextTick = IntArray(line.length + 1) { -1 }
    for (p in line.indices.reversed()) {
        nextTick[p] = if (tickRun[p] > 0) p else nextTick[p + 1]
    }
    // Whichever construct starts first owns its contents. Code can therefore contain
    // literal HTML/URLs, and unsupported links can contain literal backticks.
    i = 0
    while (i < line.length) {
        if (escaped[i]) { i++; continue }
        if (tickRun[i] > 1) {
            i += tickRun[i]
            continue
        }
        if (tickRun[i] == 1) {
            val close = nextTick[i + 1]
            if (close > i + 1 && tickRun[close] == 1) {
                removed[i] = true; removed[close] = true
                for (p in i..close) protected[p] = true
                val style = SpanStyle(fontFamily = FontFamily.Monospace)
                styles += AnswerMarker(i, 1, style) to AnswerMarker(close, 1, style)
                i = close + 1; continue
            }
        }
        val end = when {
            line.startsWith("https://", i) || line.startsWith("http://", i) -> {
                var p = i
                while (p < line.length && !line[p].isWhitespace() && line[p] != '`') p++
                p - 1
            }
            line[i] == '<' && i + 1 < line.length && (line[i + 1].isLetter() || line[i + 1] in "/!?") ->
                htmlEnd[i].takeIf { it >= i } ?: nextAngle[i + 1].let { if (it < 0) line.lastIndex else it }
            line[i] == '[' || (line[i] == '!' && i + 1 < line.length && line[i + 1] == '[') -> {
                val close = nextLink[i + 1]
                if (close < 0) -1 else matchingParen[close + 1].let { if (it < 0) line.lastIndex else it }
            }
            else -> -1
        }
        if (end >= i) {
            for (p in i..end) protected[p] = true
            i = end + 1
        } else i++
    }
    val open = mutableMapOf<String, AnswerMarker>()
    i = 0
    while (i < line.length) {
        if (protected[i]) { i++; continue }
        if (line[i] == '\\' && i + 1 < line.length && line[i + 1] in "\\`*_[]()!<>-+") {
            removed[i] = true; i += 2; continue
        }
        if (line[i] != '*' && line[i] != '_') { i++; continue }
        val length = if (i + 1 < line.length && line[i + 1] == line[i] && !protected[i + 1]) 2 else 1
        val key = line.substring(i, i + length)
        val style = if (length == 2) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontStyle = FontStyle.Italic)
        val marker = AnswerMarker(i, length, style)
        val previous = open[key]
        val canClose = i > 0 && !line[i - 1].isWhitespace()
        val canOpen = i + length < line.length && !line[i + length].isWhitespace()
        val insideWord = line[i] == '_' && i > 0 && i + length < line.length &&
            line[i - 1].isLetterOrDigit() && line[i + length].isLetterOrDigit()
        if (!insideWord && previous != null && canClose && i > previous.offset + previous.length) {
            open.remove(key)
            for (p in previous.offset until previous.offset + length) removed[p] = true
            for (p in i until i + length) removed[p] = true
            styles += previous to marker
        } else if (!insideWord && previous == null && canOpen) open[key] = marker
        i += length
    }
    val offsets = IntArray(line.length + 1)
    for (p in line.indices) {
        offsets[p] = out.length
        if (!removed[p]) out.append(line[p])
    }
    offsets[line.length] = out.length
    styles.forEach { (a, b) ->
        val start = offsets[a.offset + a.length]; val end = offsets[b.offset]
        if (end > start) out.addStyle(a.style, start, end)
    }
}
