package ru.adsrv.frpjump.files.text

/** The longest piece of a line the viewer shows as one row; a longer line goes on in further rows. */
const val ROW_CHARS = 1000

/** The most matches a search counts. */
const val MAX_MATCHES = 10_000

/**
 * A text split for the viewer: lines (without their `\n` or `\r\n`) and
 * rows, each row a piece of at most [ROW_CHARS] characters of one line,
 * so neither a long minified line nor a 50 000-line log costs more than
 * the rows on screen. Offsets index [text].
 */
class TextDocument(val text: String) {
    private val starts: IntArray
    private val ends: IntArray

    /** The line of each row. */
    private val rowLines: IntArray

    /** Where each row starts in [text]. */
    private val rowStarts: IntArray

    val lineCount: Int get() = starts.size
    val rowCount: Int get() = rowStarts.size

    /** The widest row, in characters (a tab counted as 8), for the width of unwrapped text. */
    val widestRow: Int

    init {
        val s = IntArrayList()
        val e = IntArrayList()
        var at = 0
        while (at <= text.length) {
            val nl = text.indexOf('\n', at)
            val end = if (nl < 0) text.length else nl
            // A final line break does not start one more, empty line.
            if (nl < 0 && at == text.length && s.size > 0) break
            s.add(at)
            e.add(if (end > at && text[end - 1] == '\r') end - 1 else end)
            if (nl < 0) break
            at = nl + 1
        }
        starts = s.toArray()
        ends = e.toArray()
        val rl = IntArrayList()
        val rs = IntArrayList()
        var widest = 0
        for (line in starts.indices) {
            var from = starts[line]
            do {
                rl.add(line)
                rs.add(from)
                var to = minOf(from + ROW_CHARS, ends[line])
                // A character of two halves stays whole in one row.
                if (to < ends[line] && text[to - 1].isHighSurrogate()) to--
                var width = 0
                for (k in from until to) width += if (text[k] == '\t') 8 else 1
                widest = maxOf(widest, width)
                from = to
            } while (from < ends[line])
        }
        rowLines = rl.toArray()
        rowStarts = rs.toArray()
        widestRow = widest
    }

    fun lineStart(line: Int): Int = starts[line]
    fun lineEnd(line: Int): Int = ends[line]
    fun line(line: Int): String = text.substring(starts[line], ends[line])

    fun rowLine(row: Int): Int = rowLines[row]
    fun rowStart(row: Int): Int = rowStarts[row]
    fun rowEnd(row: Int): Int = if (row + 1 < rowStarts.size && rowLines[row + 1] == rowLines[row]) rowStarts[row + 1] else ends[rowLines[row]]

    /** Whether [row] goes on a line begun in the row before (marked `↪`). */
    fun isContinuation(row: Int): Boolean = rowStarts[row] != starts[rowLines[row]]

    /** The row that holds [offset] of [text]. */
    fun rowAt(offset: Int): Int {
        var lo = 0
        var hi = rowStarts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (rowStarts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Where [query] occurs in [text], ignoring case, in order, at most [MAX_MATCHES]; none for an empty query. */
    fun find(query: String): IntArray {
        if (query.isEmpty()) return IntArray(0)
        val found = IntArrayList()
        var at = text.indexOf(query, 0, ignoreCase = true)
        while (at >= 0 && found.size < MAX_MATCHES) {
            found.add(at)
            at = text.indexOf(query, at + query.length, ignoreCase = true)
        }
        return found.toArray()
    }

    companion object {
        /** [bytes] as UTF-8, a bad sequence as U+FFFD, without a leading byte order mark. */
        fun decode(bytes: ByteArray): TextDocument = TextDocument(bytes.decodeToString().removePrefix("﻿"))
    }
}

/** A growing array of ints, without boxing. */
internal class IntArrayList {
    private var data = IntArray(64)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    fun toArray(): IntArray = data.copyOf(size)
}

/** The index of the first of [matches] (sorted starts of [length] characters) that ends after [offset]; [matches]' size when none does. */
fun firstMatchAfter(matches: IntArray, length: Int, offset: Int): Int {
    var lo = 0
    var hi = matches.size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (matches[mid] + length <= offset) lo = mid + 1 else hi = mid
    }
    return lo
}

/**
 * [text] from [start] to [end] as the viewer draws it: each control
 * character but tab shown as its visible sign (ESC as `␛`, a lone CR as
 * `␍`, DEL as `␡`), one for one, so offsets stay as they are.
 */
fun shownText(text: String, start: Int, end: Int): String {
    var i = start
    while (i < end && !isHidden(text[i])) i++
    if (i == end) return text.substring(start, end)
    val out = StringBuilder(end - start)
    for (k in start until end) {
        val c = text[k]
        out.append(
            when {
                !isHidden(c) -> c
                c == '\u007F' -> '␡'
                else -> (0x2400 + c.code).toChar()
            },
        )
    }
    return out.toString()
}

private fun isHidden(c: Char): Boolean = (c < ' ' && c != '\t') || c == '\u007F'
