package ru.adsrv.frpjump.ui.ssh

import ru.adsrv.frpjump.assistant.chat.displayCommandSpans
import java.net.IDN
import java.util.Locale

/*
 * Links in the terminal's output (decision Р37). A tap on one opens a sheet with its address; only http and https ever open, and only
 * from the sheet's "Open". Pure code: the terminal's screen is read through [TapGrid]; nothing here starts anything.
 */

/** What the sheet may do with a link. Only [OPEN] gets an "Open" button. */
enum class LinkVerdict {
    /** http or https, an address the phone can reach. */
    OPEN,

    /** http or https, but the address is this machine's own (localhost, 127.0.0.0/8, ::1, 0.0.0.0): the phone's is another one. */
    REMOTE,

    /** Any other scheme (ftp, ssh, file, a custom one): copied at most. */
    OTHER_SCHEME,

    /** The text has control, bidi or other invisible characters: copied at most. */
    HIDDEN,

    /** An http(s) address that cannot be read as one (no host, a bad port, a malformed host): copied at most. */
    INVALID,
}

/**
 * A link as the sheet shows it. [address] is what is shown, copied and, for [LinkVerdict.OPEN], opened: the link's text with a
 * non-Latin host turned into punycode. [original] is the text as the terminal had it, set only when [address] differs from it.
 * [actualHost] is set when the part before an `@` could pass for the site.
 */
data class LinkInfo(
    val text: String,
    val verdict: LinkVerdict,
    val address: String,
    val original: String?,
    /** For an openable link whose authority has an `@` (`https://accounts.google.com@evil.example/`): the host that really opens, as the browser has it. */
    val actualHost: String? = null,
)

/** The longest link taken (UTF-16 units); a longer run is not a link (no browser or intent takes more, and a screen of one is noise). */
const val MAX_LINK_CHARS = 4096

private val SCHEME = Regex("(?<![A-Za-z0-9+.\\-])[A-Za-z][A-Za-z0-9+.\\-]*://")

/** Punctuation that ends a sentence or a quote, never an address (ASCII and the common full-width forms). */
private const val TAIL_PUNCTUATION = ".,;:!?'\"…。，、；：！？”’"

/** Closing brackets, each with the opening one that makes it part of the address. */
private val CLOSERS = mapOf(
    ')' to '(', ']' to '[', '}' to '{', '>' to '<',
    '）' to '（', '」' to '「', '』' to '『', '】' to '【', '》' to '《', '〉' to '〈',
)

/** Characters that end an address: any space or control character, and the ones no address holds (quotes, angle brackets, a backtick). */
internal fun endsLink(codePoint: Int): Boolean =
    codePoint < 0x20 || codePoint in 0x7F..0x9F || Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) ||
        codePoint == '"'.code || codePoint == '<'.code || codePoint == '>'.code || codePoint == '`'.code

/** A link in a text: [start] until [end] (exclusive), after the tail was cut. */
data class LinkSpan(val start: Int, val end: Int)

/** Every link in [text], in order. */
fun findLinks(text: String): List<LinkSpan> {
    val spans = ArrayList<LinkSpan>()
    var from = 0
    while (from < text.length) {
        val match = SCHEME.find(text, from) ?: break
        val afterScheme = match.range.last + 1
        var end = afterScheme
        while (end < text.length) {
            val cp = text.codePointAt(end)
            if (endsLink(cp)) break
            end += Character.charCount(cp)
        }
        val cut = trimLinkTail(text, match.range.first, end, afterScheme)
        from = maxOf(end, afterScheme)
        if (cut > afterScheme && cut - match.range.first <= MAX_LINK_CHARS) spans += LinkSpan(match.range.first, cut)
    }
    return spans
}

/**
 * Where the link in [text] from [start] until [end] really ends: sentence punctuation and quotes at its end are cut, and so is a
 * closing bracket with no opening one before it in the link (`(see https://a.b/x)` loses the `)`, `https://a.b/f_(x)` keeps it). Never
 * cuts into the part up to [floor] (the scheme).
 */
internal fun trimLinkTail(text: String, start: Int, end: Int, floor: Int = start): Int {
    // How many of each bracket the link holds now; one pass, then each cut character is taken off, so a long run of brackets stays cheap.
    val counts = HashMap<Char, Int>()
    for (i in start until end) {
        val c = text[i]
        if (c in CLOSERS || c in OPENERS) counts[c] = (counts[c] ?: 0) + 1
    }
    var e = end
    while (e > floor) {
        val c = text[e - 1]
        val opener = CLOSERS[c]
        val cut = c in TAIL_PUNCTUATION || (opener != null && (counts[c] ?: 0) > (counts[opener] ?: 0))
        if (!cut) break
        if (c in CLOSERS || c in OPENERS) counts[c] = (counts[c] ?: 1) - 1
        e--
    }
    return e
}

private val OPENERS = CLOSERS.values.toSet()

/**
 * What to do with [text], a link as found by [findLinks]: the verdict and the address shown. The text is judged as a whole by the
 * rule the assistant's confirmation card uses for invisible characters ([displayCommandSpans]).
 */
fun describeLink(text: String): LinkInfo {
    fun without(verdict: LinkVerdict) = LinkInfo(text, verdict, text, null)
    val schemeEnd = text.indexOf("://")
    if (schemeEnd <= 0) return without(LinkVerdict.OTHER_SCHEME)
    if (displayCommandSpans(text).any { it.escape }) return without(LinkVerdict.HIDDEN)
    val scheme = text.substring(0, schemeEnd).lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return without(LinkVerdict.OTHER_SCHEME)

    val authorityStart = schemeEnd + 3
    var authorityEnd = authorityStart
    // A browser ends the authority at a backslash as at a slash.
    while (authorityEnd < text.length && text[authorityEnd] !in "/\\?#") authorityEnd++
    val authority = text.substring(authorityStart, authorityEnd)
    val at = authority.lastIndexOf('@')
    val hostPort = authority.substring(at + 1)
    val hostStart = authorityStart + at + 1
    val bracketed = hostPort.startsWith("[")
    val host: String
    val portPart: String
    if (bracketed) {
        val close = hostPort.indexOf(']')
        if (close < 0) return without(LinkVerdict.INVALID)
        host = hostPort.substring(1, close)
        portPart = hostPort.substring(close + 1)
    } else {
        val colon = hostPort.lastIndexOf(':')
        host = if (colon >= 0) hostPort.substring(0, colon) else hostPort
        portPart = if (colon >= 0) hostPort.substring(colon) else ""
    }
    if (host.isEmpty() || (portPart.isNotEmpty() && !(portPart.startsWith(":") && portPart.drop(1).all { it in '0'..'9' }))) {
        return without(LinkVerdict.INVALID)
    }
    if (!bracketed && ':' in host) return without(LinkVerdict.INVALID)

    var asciiHost = host
    if (host.any { it.code > 0x7F }) {
        asciiHost = try { IDN.toASCII(host, IDN.ALLOW_UNASSIGNED) } catch (e: IllegalArgumentException) { return without(LinkVerdict.INVALID) }
    }
    val verdict = when (hostKind(asciiHost, bracketed)) {
        HostKind.REMOTE -> LinkVerdict.REMOTE
        HostKind.INVALID -> LinkVerdict.INVALID
        HostKind.OTHER -> LinkVerdict.OPEN
    }
    // Before an @ is userinfo, not the site: say which host opens, so a trusted name there does not pass for it.
    val actual = if (verdict == LinkVerdict.OPEN && at >= 0) (if (bracketed) "[$asciiHost]" else asciiHost) else null
    if (asciiHost == host) return LinkInfo(text, verdict, text, null, actual)
    val hostEnd = hostStart + (if (bracketed) host.length + 2 else host.length)
    val address = text.substring(0, hostStart) + (if (bracketed) "[$asciiHost]" else asciiHost) + text.substring(hostEnd)
    return LinkInfo(text, verdict, address, text, actual)
}

/**
 * The address that may be handed to the system's browser for [text], or null: only an http or https link that is not hidden, invalid
 * or on the remote machine. The one gate in front of every launch.
 */
fun linkOpenTarget(text: String): String? = describeLink(text).takeIf { it.verdict == LinkVerdict.OPEN }?.address

private enum class HostKind { REMOTE, OTHER, INVALID }

/** Whether [host] (ASCII, no brackets) names the machine the link was printed on, as a browser would read it. */
private fun hostKind(host: String, bracketed: Boolean): HostKind {
    if (bracketed) {
        val groups = parseIpv6(host) ?: return HostKind.INVALID
        val zeroHead = (0 until 5).all { groups[it] == 0 }
        val v4Mapped = zeroHead && groups[5] == 0xFFFF
        return when {
            zeroHead && groups[5] == 0 && groups[6] == 0 && (groups[7] == 0 || groups[7] == 1) -> HostKind.REMOTE
            v4Mapped && (groups[6] ushr 8 == 127 || groups[6] ushr 8 == 0) -> HostKind.REMOTE
            else -> HostKind.OTHER
        }
    }
    var decoded = percentDecode(host) ?: return HostKind.INVALID
    // What the browser makes of it: full-width letters, a soft hyphen and the like fold to the plain name (127.0.0.%EF%BC%91 is 127.0.0.1).
    if (decoded.any { it.code > 0x7F }) {
        decoded = try { IDN.toASCII(decoded, IDN.ALLOW_UNASSIGNED) } catch (e: IllegalArgumentException) { return HostKind.INVALID }
    }
    // A delimiter that only shows after decoding or folding: the launcher would read the address differently from this check.
    if (decoded.any { it in "@:/\\?#[]%" }) return HostKind.INVALID
    val name = decoded.lowercase(Locale.ROOT).trimEnd('.')
    if (name.isEmpty()) return HostKind.INVALID
    if (name == "localhost" || name.endsWith(".localhost")) return HostKind.REMOTE
    val labels = name.split('.')
    if (isNumberLabel(labels.last())) {
        // A host ending in a number is an IPv4 address in any of the forms a browser takes (127.1, 0x7f.1, 2130706433) or nothing.
        val address = parseIpv4(labels) ?: return HostKind.INVALID
        val top = (address ushr 24).toInt()
        return if (top == 127 || top == 0) HostKind.REMOTE else HostKind.OTHER
    }
    return HostKind.OTHER
}

private fun isNumberLabel(label: String): Boolean =
    label.isNotEmpty() && (label.all { it in '0'..'9' } || (label.startsWith("0x", ignoreCase = true) && label.drop(2).all { it.isHexDigit() }))

private fun Char.isHexDigit() = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

private fun parseNumberLabel(label: String): Long? {
    if (label.isEmpty()) return null
    return try {
        when {
            label.startsWith("0x", ignoreCase = true) -> if (label.length == 2) 0 else label.substring(2).toLong(16)
            label.length > 1 && label.startsWith("0") -> label.substring(1).toLong(8)
            else -> label.toLong(10)
        }
    } catch (e: NumberFormatException) { null }
}

/** WHATWG IPv4 parsing of [labels]; null when it is not an address. */
private fun parseIpv4(labels: List<String>): Long? {
    if (labels.size > 4) return null
    val numbers = labels.map { parseNumberLabel(it) ?: return null }
    if (numbers.dropLast(1).any { it > 255 }) return null
    val last = numbers.last()
    var limit = 1L
    repeat(5 - numbers.size) { limit *= 256 }
    if (last >= limit) return null
    var address = last
    numbers.dropLast(1).forEachIndexed { i, n ->
        var shift = 1L
        repeat(3 - i) { shift *= 256 }
        address += n * shift
    }
    return address
}

/** The eight 16-bit groups of an IPv6 literal (without brackets, no zone); null when it is not valid. */
private fun parseIpv6(text: String): IntArray? {
    if (text.isEmpty() || '%' in text) return null
    val doubleColon = text.indexOf("::")
    if (doubleColon >= 0 && text.indexOf("::", doubleColon + 1) >= 0) return null
    fun groups(part: String): List<Int>? {
        if (part.isEmpty()) return emptyList()
        val items = part.split(':')
        val out = ArrayList<Int>()
        for ((index, item) in items.withIndex()) {
            if (index == items.lastIndex && '.' in item) {
                val octets = item.split('.')
                if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || !it.all { c -> c in '0'..'9' } || it.toInt() > 255 }) return null
                out += octets[0].toInt() * 256 + octets[1].toInt()
                out += octets[2].toInt() * 256 + octets[3].toInt()
            } else {
                if (item.isEmpty() || item.length > 4 || !item.all { it.isHexDigit() }) return null
                out += item.toInt(16)
            }
        }
        return out
    }
    val result = IntArray(8)
    if (doubleColon < 0) {
        val all = groups(text) ?: return null
        if (all.size != 8) return null
        all.forEachIndexed { i, g -> result[i] = g }
    } else {
        val head = groups(text.substring(0, doubleColon)) ?: return null
        val tail = groups(text.substring(doubleColon + 2)) ?: return null
        if (head.size + tail.size > 7) return null
        head.forEachIndexed { i, g -> result[i] = g }
        tail.forEachIndexed { i, g -> result[8 - tail.size + i] = g }
    }
    return result
}

/** [text] with its %XX escapes decoded as UTF-8, or null when one is malformed. */
private fun percentDecode(text: String): String? {
    if ('%' !in text) return text
    val bytes = java.io.ByteArrayOutputStream()
    var i = 0
    while (i < text.length) {
        val c = text[i]
        if (c == '%') {
            if (i + 2 >= text.length) return null
            val hex = text.substring(i + 1, i + 3)
            if (!hex.all { it.isHexDigit() }) return null
            bytes.write(hex.toInt(16))
            i += 3
        } else {
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
    }
    return bytes.toString(Charsets.UTF_8.name())
}

// ---------------------------------------------------------------------------------------------------------------------------------
// Reading links off the terminal's screen

/**
 * Rows [firstRow]..[lastRow] of a grid read as one text, the cells as the emulator laid them out; [indexAt] says where in [text] the
 * cell at (row, column) starts (the right half of a wide character is its left half's).
 */
internal class RowChain(val firstRow: Int, val lastRow: Int, val text: String, private val columns: Int, private val cellStarts: IntArray) {
    fun indexAt(row: Int, column: Int): Int = cellStarts[(row - firstRow) * columns + column.coerceIn(0, columns - 1)]
}

/**
 * The rows [from]..[to] of [grid] cut into logical lines. A row joins the next when the emulator wrapped it, and, with [hardWrapped]
 * (tmux draws every row on its own and marks no wrap), also when a link is running into the last column and the next row begins with
 * address characters: the owner sees the whole address in the sheet before anything opens.
 */
internal fun rowChains(grid: TapGrid, hardWrapped: Boolean, from: Int, to: Int): List<RowChain> {
    val columns = grid.columns
    if (columns <= 0 || from > to) return emptyList()
    val chains = ArrayList<RowChain>()
    var first = from
    var text = StringBuilder()
    var starts = ArrayList<Int>()
    var skipIndent = 0
    for (row in from..to) {
        for (column in 0 until columns) {
            if (column < skipIndent) { starts += text.length; continue }
            val gap = column == columns - 1 && row < to && grid.wraps(row) && grid.isBlank(row, column) && columns > 1 && grid.isWideTail(row + 1, 1)
            if (grid.isWideTail(row, column)) {
                starts += starts.lastOrNull() ?: text.length
            } else {
                starts += text.length
                if (!gap) text.append(grid.text(row, column))
            }
        }
        val indent = if (row < to && hardWrapped && !grid.wraps(row)) enclosedContinuationIndent(grid, row) else null
        val joins = row < to && (grid.wraps(row) || (hardWrapped && (indent != null || runsIntoNextRow(grid, row, text))))
        skipIndent = indent ?: 0
        if (!joins || row == to) {
            chains += RowChain(first, row, text.toString(), columns, starts.toIntArray())
            first = row + 1
            text = StringBuilder()
            starts = ArrayList()
        }
    }
    return chains
}

/** A separately drawn, indented parenthesized Web address with a complete closing delimiter on the next row. */
private fun enclosedContinuationIndent(grid: TapGrid, row: Int): Int? {
    val columns = grid.columns
    if (columns < 4 || grid.isBlank(row, columns - 1) || grid.isWideTail(row, columns - 1)) return null
    var indent = 0
    while (indent < columns && grid.isBlank(row, indent)) indent++
    if (indent == 0 || indent + 1 >= columns || grid.text(row, indent) != "(") return null
    val prefix = StringBuilder()
    for (column in indent + 1 until columns) {
        if (grid.isWideTail(row, column)) continue
        val cell = grid.text(row, column)
        if (endsLink(cell.codePointAtOrSpace()) || cell == "(" || cell == ")") return null
        prefix.append(cell)
    }
    val address = prefix.toString()
    val authority = when {
        address.startsWith("https://", true) -> 8
        address.startsWith("http://", true) -> 7
        else -> return null
    }
    if (address.indexOf('/', authority) <= authority || describeLink(address).verdict !in setOf(LinkVerdict.OPEN, LinkVerdict.REMOTE)) return null
    for (column in 0 until indent) if (!grid.isBlank(row + 1, column)) return null
    if (indent >= columns || grid.isBlank(row + 1, indent)) return null
    val suffix = StringBuilder()
    var closedAt = -1
    for (column in indent until columns) {
        if (grid.isWideTail(row + 1, column)) continue
        val cell = grid.text(row + 1, column)
        if (cell == ")") { closedAt = column; break }
        if (endsLink(cell.codePointAtOrSpace()) || cell == "(") return null
        suffix.append(cell)
    }
    if (suffix.isEmpty() || closedAt < 0 || SCHEME.containsMatchIn(suffix)) return null
    if (closedAt + 1 < columns && !grid.isBlank(row + 1, closedAt + 1) && grid.text(row + 1, closedAt + 1) !in listOf(".", ",", ";", ":", "!", "?")) return null
    return indent
}

/** tmux's case: the link in [text] (the lines so far) reaches [row]'s last column and the next row goes on with address characters. */
private fun runsIntoNextRow(grid: TapGrid, row: Int, text: CharSequence): Boolean {
    val last = grid.columns - 1
    if (grid.isBlank(row, last) || grid.isWideTail(row, last)) return false
    if (grid.isBlank(row + 1, 0) || endsLink(grid.text(row + 1, 0).codePointAtOrSpace())) return false
    // A new scheme at the next row is a separate address, even when the first fills the margin.
    val next = StringBuilder()
    for (column in 0 until minOf(grid.columns, MAX_LINK_CHARS + 1)) {
        if (grid.isWideTail(row + 1, column)) continue
        val cell = grid.text(row + 1, column)
        if (endsLink(cell.codePointAtOrSpace())) break
        next.append(cell)
    }
    val firstScheme = next.indexOfFirst { it !in OPENERS && it !in "'‘“" }.takeIf { it >= 0 } ?: return false
    if (SCHEME.find(next, firstScheme)?.range?.first == firstScheme) return false
    var tokenStart = text.length
    while (tokenStart > 0 && text.length - tokenStart <= MAX_LINK_CHARS && !endsLink(Character.codePointBefore(text, tokenStart))) tokenStart -= Character.charCount(Character.codePointBefore(text, tokenStart))
    if (text.length - tokenStart > MAX_LINK_CHARS) return false
    return SCHEME.containsMatchIn(text.subSequence(tokenStart, text.length))
}

private fun String.codePointAtOrSpace(): Int = if (isEmpty()) ' '.code else codePointAt(0)

/** The link under the cell (row, column), as its text in the terminal, or null. */
fun linkAt(grid: TapGrid, hardWrapped: Boolean, row: Int, column: Int): String? {
    if (grid.columns <= 0 || row !in grid.firstRow..grid.lastRow) return null
    val reach = MAX_LINK_CHARS / grid.columns + 2
    val chain = rowChains(grid, hardWrapped, maxOf(grid.firstRow, row - reach), minOf(grid.lastRow, row + reach))
        .firstOrNull { row in it.firstRow..it.lastRow } ?: return null
    if (grid.isBlank(row, column.coerceIn(0, grid.columns - 1))) return null
    val index = chain.indexAt(row, column)
    if (index !in chain.text.indices || chain.text[index] == ' ') return null
    val span = findLinks(chain.text).firstOrNull { index >= it.start && index < it.end } ?: return null
    return chain.text.substring(span.start, span.end)
}

/**
 * The links on the rows the screen shows now, each once, in reading order. [topRow] is the first visible row (0, or negative while
 * the view is scrolled back into the history); the screen is as tall as the grid's rows from 0 to its last one.
 */
fun linksOnScreen(grid: TapGrid, hardWrapped: Boolean, topRow: Int = 0, limit: Int = 100): List<String> {
    if (grid.columns <= 0 || grid.lastRow < 0) return emptyList()
    val first = topRow.coerceIn(grid.firstRow, 0)
    val last = minOf(first + grid.lastRow, grid.lastRow)
    val reach = MAX_LINK_CHARS / grid.columns + 2
    val found = LinkedHashSet<String>()
    for (chain in rowChains(grid, hardWrapped, maxOf(grid.firstRow, first - reach), minOf(grid.lastRow, last + reach))) {
        val visibleFirst = maxOf(first, chain.firstRow)
        val visibleLast = minOf(last, chain.lastRow)
        if (visibleFirst > visibleLast) continue
        val start = chain.indexAt(visibleFirst, 0)
        val end = if (visibleLast < chain.lastRow) chain.indexAt(visibleLast + 1, 0) else chain.text.length
        for (span in findLinks(chain.text)) {
            if (span.start >= end || span.end <= start) continue
            found += chain.text.substring(span.start, span.end)
            if (found.size >= limit) return found.toList()
        }
    }
    return found.toList()
}

/** Where a tap on the terminal goes. */
sealed interface TapRoute {
    /** The shell's cursor moves by [moves] (see [tapCursorMoves]). */
    data class Cursor(val moves: Int) : TapRoute

    /** The link's sheet opens for [text]. */
    data class Link(val text: String) : TapRoute

    /** Neither: the view acts as it always did (keyboard, a click for a full-screen program). */
    data object Pass : TapRoute
}

/**
 * The route of a tap at ([row], [column]). The cursor's line comes first (when the shell reads it, [onShellLine]): a tap there stays the
 * cursor's even when an address is typed on it. Anywhere else a tap on a link is the link's.
 */
fun routeTap(grid: TapGrid, onShellLine: Boolean, hardWrapped: Boolean, cursorRow: Int, cursorColumn: Int, row: Int, column: Int): TapRoute {
    val moves = if (onShellLine) tapCursorMoves(grid, cursorRow, cursorColumn, row, column) else null
    if (moves != null) return TapRoute.Cursor(moves)
    return linkAt(grid, hardWrapped, row, column)?.let { TapRoute.Link(it) } ?: TapRoute.Pass
}
