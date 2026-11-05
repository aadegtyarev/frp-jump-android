package ru.adsrv.frpjump.ui.settings

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight

private val answerList = Regex("^( {0,3})([-+*])[ \\t]+(.*)$")
private val htmlName = Regex("^<(/?)([A-Za-z][A-Za-z0-9-]*)")
private const val LITERAL = 1
private const val CODE = 2
private const val CODE_MARKER = 3

/** Display-only subset. Classify literal/code regions before any destructive formatting. */
internal fun formatAssistantAnswer(source: String): AnnotatedString {
    val regions = answerRegions(source)
    val out = AnnotatedString.Builder()
    var pos = 0
    while (pos < source.length) {
        val end = source.indexOf('\n', pos).let { if (it < 0) source.length else it }
        val line = source.substring(pos, end)
        // A supported fence is marked with CODE_MARKER, with its payload marked CODE.
        if (line.startsWith("```") && regions[pos] == CODE_MARKER) {
            var closing = end + 1
            while (closing < source.length && regions[closing] != CODE_MARKER) closing++
            val start = out.length
            if (end < source.length && closing <= source.length) out.append(source.substring(end + 1, closing))
            if (out.length > start) out.addStyle(SpanStyle(fontFamily = FontFamily.Monospace), start, out.length)
            val closeEnd = source.indexOf('\n', closing).let { if (it < 0) source.length else it }
            if (closeEnd < source.length && (closing == end + 1 || source[closing - 1] != '\n')) out.append('\n')
            pos = if (closeEnd < source.length) closeEnd + 1 else closeEnd
            continue
        }
        val list = answerList.matchEntire(line)
        val body = list?.groups?.get(3)?.range?.first ?: 0
        if (list != null && (pos until pos + body).none { regions[it] != 0 }) {
            out.append(list.groupValues[1]); out.append("• ")
            appendInline(out, line.substring(body), regions, pos + body)
        } else appendInline(out, line, regions, pos)
        if (end < source.length) out.append('\n')
        pos = if (end < source.length) end + 1 else end
    }
    return out.toAnnotatedString()
}

/** End of a tag, ignoring angle brackets in quoted attributes. Unterminated tags stay literal. */
private fun htmlTagEnd(text: String, start: Int): Int {
    var quote: Char? = null
    var p = start + 1
    while (p < text.length) {
        val c = text[p++]
        if (quote != null) {
            if (c == quote) quote = null
        } else when (c) {
            '\'', '"' -> quote = c
            '>' -> return p
        }
    }
    return text.length
}

/** Returns the complete inert HTML-shaped region, including nested same-name containers. */
private fun htmlLiteralEnd(text: String, start: Int): Int {
    if (text.startsWith("<!--", start)) {
        val close = text.indexOf("-->", start + 4)
        return if (close < 0) text.length else close + 3
    }
    val firstEnd = htmlTagEnd(text, start)
    val first = text.substring(start, firstEnd)
    val tag = htmlName.find(first)
    if (tag == null || tag.groupValues[1].isNotEmpty() || first.trimEnd().endsWith("/>")) return firstEnd
    val name = tag.groupValues[2]
    var depth = 1
    var scan = firstEnd
    while (scan < text.length) {
        val open = text.indexOf('<', scan)
        if (open < 0) break
        if (text.startsWith("<!--", open)) {
            val close = text.indexOf("-->", open + 4)
            if (close < 0) return text.length
            scan = close + 3
            continue
        }
        val end = htmlTagEnd(text, open)
        val candidate = text.substring(open, end)
        val nextTag = htmlName.find(candidate)
        if (nextTag != null && nextTag.groupValues[2].equals(name, true)) {
            if (nextTag.groupValues[1] == "/") depth--
            else if (!candidate.trimEnd().endsWith("/>")) depth++
            if (depth == 0) return end
        }
        scan = end
    }
    return text.length
}

private fun startsHtml(text: String, i: Int) = text[i] == '<' && i + 1 < text.length &&
    (text[i + 1].isLetter() || text[i + 1] in "/!?")

/** Nonrecursive, forward-only classification. Unknown constructs remain literal, never executable. */
private fun answerRegions(text: String): IntArray {
    val kind = IntArray(text.length)
    fun mark(start: Int, end: Int, value: Int) { for (p in start until end) kind[p] = value }
    fun lineEnd(start: Int) = text.indexOf('\n', start).let { if (it < 0) text.length else it }
    // A malformed opaque fragment can hide the label's closing bracket. In that
    // ambiguous case retain the candidate link instead of formatting its prefix.
    val nextLinkEnd = IntArray(text.length + 1) { -1 }
    val nextOpaque = IntArray(text.length + 1) { -1 }
    for (p in text.indices.reversed()) {
        nextLinkEnd[p] = if (text[p] == ']' && p + 1 < text.length && text[p + 1] == '(') p else nextLinkEnd[p + 1]
        nextOpaque[p] = if (text[p] == '`' || startsHtml(text, p)) p else nextOpaque[p + 1]
    }
    val linkClose = IntArray(text.length) { -1 }
    val brackets = ArrayDeque<Int>()
    var bracketScan = 0
    while (bracketScan < text.length) {
        if (text[bracketScan] == '\\' && bracketScan + 1 < text.length) {
            if (startsHtml(text, bracketScan + 1)) {
                bracketScan = htmlLiteralEnd(text, bracketScan + 1)
                continue
            }
            if (text[bracketScan + 1] == '`' &&
                (bracketScan + 2 >= text.length || text[bracketScan + 2] != '`')) {
                // Escaped ticks in a label can themselves enclose bracket text.
                val endOfLine = lineEnd(bracketScan)
                val closing = text.indexOf("\\`", bracketScan + 2)
                bracketScan = if (closing >= 0 && closing < endOfLine) closing + 2 else bracketScan + 2
                continue
            }
            if (text[bracketScan + 1] != '`') { bracketScan += 2; continue }
            bracketScan++ // Escaped multi-tick wrappers are opaque too.
        }
        if (startsHtml(text, bracketScan)) {
            bracketScan = htmlLiteralEnd(text, bracketScan)
            continue
        }
        if (text[bracketScan] == '`') {
            var runEnd = bracketScan + 1
            while (runEnd < text.length && text[runEnd] == '`') runEnd++
            val count = runEnd - bracketScan
            val lineEnd = lineEnd(runEnd)
            val atLineStart = bracketScan == 0 || text[bracketScan - 1] == '\n'
            val fence = atLineStart && lineEnd < text.length &&
                ((count >= 3 && text.substring(runEnd, lineEnd).none { it == '`' }) ||
                    (count < 3 && text.substring(runEnd, lineEnd).isBlank()))
            if (fence) {
                var scan = lineEnd + 1
                var end = text.length
                while (scan < text.length) {
                    val e = lineEnd(scan)
                    val trimmed = text.substring(scan, e).trim()
                    if (trimmed.length == count && trimmed.all { it == '`' }) { end = e; break }
                    scan = if (e < text.length) e + 1 else e
                }
                bracketScan = end
                continue
            }
            val close = text.indexOf('`', runEnd)
            if (count == 1 && (close < 0 || close >= lineEnd)) {
                bracketScan = lineEnd
            } else if (close < 0) {
                bracketScan = text.length
            } else {
                bracketScan = close + 1
                while (bracketScan < text.length && text[bracketScan] == '`') bracketScan++
            }
            continue
        }
        if (text[bracketScan] == '[') brackets.addLast(bracketScan)
        if (text[bracketScan] == ']' && brackets.isNotEmpty()) linkClose[brackets.removeLast()] = bracketScan
        bracketScan++
    }
    var i = 0
    while (i < text.length) {
        val atLineStart = i == 0 || text[i - 1] == '\n'
        if (atLineStart && text[i] == '`') {
            var runEnd = i
            while (runEnd < text.length && text[runEnd] == '`') runEnd++
            val count = runEnd - i
            val openEnd = lineEnd(i)
            if ((count >= 3 && text.substring(runEnd, openEnd).none { it == '`' }) ||
                (count < 3 && openEnd < text.length && text.substring(runEnd, openEnd).isBlank())) {
                var scan = if (openEnd < text.length) openEnd + 1 else openEnd
                var close = -1
                var mismatch = false
                while (scan < text.length) {
                    val e = lineEnd(scan)
                    val trimmed = text.substring(scan, e).trim()
                    if (trimmed.isNotEmpty() && trimmed.all { it == '`' }) {
                        if (trimmed.length == count) { close = scan; break }
                        if (count != 3 || trimmed.length >= 3) mismatch = true
                    }
                    scan = if (e < text.length) e + 1 else e
                }
                if (count == 3 && close >= 0 && !mismatch) {
                    mark(i, openEnd, CODE_MARKER)
                    mark(openEnd + 1, close, CODE)
                    val e = lineEnd(close)
                    mark(close, e, CODE_MARKER)
                    i = e
                } else {
                    val e = if (close >= 0) lineEnd(close) else text.length
                    mark(i, e, LITERAL); i = e
                }
                continue
            }
        }
        // A backslash may escape an opener, but never escapes a closing code tick.
        var tick = i
        if (text[i] == '\\' && i + 1 < text.length) {
            if (text[i + 1] != '`' || i + 2 >= text.length || text[i + 2] != '`') { i += 2; continue }
            tick = i + 1
        }
        if (text[tick] == '`') {
            val eol = lineEnd(tick)
            var runEnd = tick
            while (runEnd < eol && text[runEnd] == '`') runEnd++
            val count = runEnd - tick
            val next = text.indexOf('`', runEnd).let { if (it < 0 || it >= eol) -1 else it }
            var nextEnd = next
            if (next >= 0) while (nextEnd < eol && text[nextEnd] == '`') nextEnd++
            val closeCount = if (next >= 0) nextEnd - next else 0
            if (i == tick && count == 1 && next > runEnd && closeCount == 1) {
                kind[tick] = CODE_MARKER; mark(runEnd, next, CODE); kind[next] = CODE_MARKER
                i = nextEnd
            } else if (count > 1 || closeCount > 1) {
                // Unsupported multi-tick wrappers can span lines. Preserve their
                // contents too, not just the delimiters; mismatches stay literal.
                val other = if (count > 1) text.indexOf('`', runEnd) else next
                var otherEnd = other
                if (other >= 0) while (otherEnd < text.length && text[otherEnd] == '`') otherEnd++
                val end = if (count > 1 && other >= 0 && otherEnd - other == count) otherEnd else text.length
                mark(i, end, LITERAL); i = end
            } else if (i == tick && count == 1 && next < 0) {
                mark(i, eol, LITERAL); i = eol
            } else i = runEnd
            continue
        }
        if (text.startsWith("http://", i) || text.startsWith("https://", i)) {
            var e = i
            while (e < text.length && !text[e].isWhitespace() && text[e] != '`') e++
            mark(i, e, LITERAL); i = e; continue
        }
        if (startsHtml(text, i)) {
            val end = htmlLiteralEnd(text, i)
            mark(i, end, LITERAL); i = end; continue
        }
        if (text[i] == '[' || (text[i] == '!' && i + 1 < text.length && text[i + 1] == '[')) {
            val bracket = if (text[i] == '!') i + 1 else i
            val matched = linkClose[bracket]
            val ambiguous = nextLinkEnd[bracket + 1]
            val opaque = nextOpaque[bracket + 1]
            if (matched < 0 && ambiguous >= 0 && opaque in (bracket + 1) until ambiguous) {
                // The opaque fragment's end is ambiguous too, so don't expose
                // any following lines to list/escape/emphasis transformations.
                mark(i, text.length, LITERAL); break
            }
            val close = matched
            if (close >= 0 && close + 1 < text.length && text[close + 1] == '(') {
                var depth = 1
                var p = close + 2
                while (p < text.length && depth > 0) {
                    if (text[p] == '\\' && p + 1 < text.length) { p += 2; continue }
                    if (startsHtml(text, p)) { p = htmlLiteralEnd(text, p); continue }
                    if (text[p] == '\'' || text[p] == '"') {
                        val quote = text[p++]
                        while (p < text.length && text[p] != quote) {
                            if (text[p] == '\\' && p + 1 < text.length) p++
                            p++
                        }
                        if (p < text.length) p++
                        continue
                    }
                    if (text[p] == '`') {
                        var e = p + 1
                        while (e < text.length && text[e] == '`') e++
                        val end = text.indexOf('`', e)
                        if (end < 0) { p = text.length; continue }
                        p = end + 1
                        while (p < text.length && text[p] == '`') p++
                        continue
                    }
                    if (text[p] == '(') depth++ else if (text[p] == ')') depth--
                    p++
                }
                mark(i, p, LITERAL); i = p; continue
            }
        }
        i++
    }
    return kind
}

private data class AnswerMarker(val offset: Int, val length: Int, val style: SpanStyle)

private fun appendInline(out: AnnotatedString.Builder, line: String, regions: IntArray, base: Int) {
    val removed = BooleanArray(line.length)
    val styles = mutableListOf<Pair<AnswerMarker, AnswerMarker>>()
    var p = 0
    while (p < line.length) {
        when (regions[base + p]) {
            CODE_MARKER -> { removed[p] = true; p++ }
            CODE -> {
                val start = p
                while (p < line.length && regions[base + p] == CODE) p++
                val style = SpanStyle(fontFamily = FontFamily.Monospace)
                styles += AnswerMarker(start, 0, style) to AnswerMarker(p, 0, style)
            }
            else -> p++
        }
    }
    val open = mutableMapOf<String, AnswerMarker>()
    var i = 0
    while (i < line.length) {
        if (regions[base + i] != 0) { i++; continue }
        if (line[i] == '\\' && i + 1 < line.length && regions[base + i + 1] == 0 && line[i + 1] in "\\`*_[]()!<>-+") {
            removed[i] = true; i += 2; continue
        }
        if (line[i] != '*' && line[i] != '_') { i++; continue }
        val length = if (i + 1 < line.length && line[i + 1] == line[i] && regions[base + i + 1] == 0) 2 else 1
        val key = line.substring(i, i + length)
        val style = if (length == 2) SpanStyle(fontWeight = FontWeight.Bold) else SpanStyle(fontStyle = FontStyle.Italic)
        val marker = AnswerMarker(i, length, style)
        val previous = open[key]
        val canClose = i > 0 && !line[i - 1].isWhitespace()
        val canOpen = i + length < line.length && !line[i + length].isWhitespace()
        val insideWord = line[i] == '_' && i > 0 && i + length < line.length && line[i - 1].isLetterOrDigit() && line[i + length].isLetterOrDigit()
        if (!insideWord && previous != null && canClose && i > previous.offset + previous.length) {
            open.remove(key)
            for (q in previous.offset until previous.offset + length) removed[q] = true
            for (q in i until i + length) removed[q] = true
            styles += previous to marker
        } else if (!insideWord && previous == null && canOpen) open[key] = marker
        i += length
    }
    val offsets = IntArray(line.length + 1)
    for (q in line.indices) { offsets[q] = out.length; if (!removed[q]) out.append(line[q]) }
    offsets[line.length] = out.length
    styles.forEach { (a, b) ->
        val start = offsets[a.offset + a.length]; val end = offsets[b.offset]
        if (end > start) out.addStyle(a.style, start, end)
    }
}
