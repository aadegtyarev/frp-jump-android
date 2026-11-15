package ru.adsrv.frpjump.assistant

/** What a read of a tab asks of its output source. */
internal sealed interface ReadMode {
    /** The visible screen with up to [lines] rows of history, wrapped lines joined: what `terminal_read` shows without `from`. */
    data class Screen(val lines: Int) : ReadMode
    /**
     * The physical rows from [cursor] on, from three rows above it to check it; when rows may have been dropped since, from as far above it as one
     * trim of the history can have moved it, and with [full] the whole kept history (the last fallback).
     */
    data class From(val cursor: TabCursor, val full: Boolean = false) : ReadMode
}

/**
 * What a tab's ONE output source holds (a tmux tab's own history and screen from the device, any other tab's terminal buffer from the app;
 * nothing else keeps a tab's output: no second buffer, no log). Rows are numbered from the oldest kept row, as the physical rows of the terminal
 * (a wrapped line is several rows).
 * - [text]: the rows read, oldest first, separated by line breaks, down to the bottom of the screen. For [ReadMode.Screen] wrapped lines are joined and
 *   [firstRow] is -1; for [ReadMode.From] they are physical rows and [firstRow] is the number of the first of them.
 * - [cursorRow]: the row the terminal's cursor is on (history rows + the cursor's row on the screen); [above]: up to three physical rows just above it.
 * - [historyRows]: rows of history kept now. [noDrop]: the source cannot have dropped rows (the app's buffer below its capacity; tmux's history
 *   below 90 % of its limit: tmux drops a tenth at once when it is full).
 */
internal class SourceText(
    val text: String, val firstRow: Int, val cursorRow: Int, val above: List<String>, val historyRows: Int, val noDrop: Boolean,
    /** For a window read of tmux's history: the rows one trim of it drops (a tenth of its limit); -1 for any other read. */
    val trim: Int = -1,
)

/**
 * A position in a tab's output, as the model sees it: opaque text, `<row>.<history>.<8 hex>`. [position] is the row the terminal's cursor was on,
 * [history] how many rows of history were kept then, [sum] a checksum of the (up to) three rows just above that row. Only [TabText] knows what they mean.
 */
internal class TabCursor(val position: Int, val history: Int, val sum: Int) {
    override fun toString() = "$position.$history.${"%08x".format(sum)}"
    override fun equals(other: Any?) = other is TabCursor && other.position == position && other.history == history && other.sum == sum
    override fun hashCode() = (31 * position + history) * 31 + sum

    companion object {
        /** The longest cursor text. */
        const val MAX_CHARS = 32
        private val FORMAT = Regex("(\\d{1,9})\\.(\\d{1,9})\\.([0-9a-f]{8})")

        /** The cursor [raw] spells, or null when it is not one this app made. */
        fun parse(raw: String): TabCursor? {
            val m = FORMAT.matchEntire(raw) ?: return null
            return TabCursor(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toLong(16).toInt())
        }
    }
}

/** The rows a read starts above the cursor's row when rows may have dropped since: one trim of the history (tmux drops a tenth at once). */
internal fun trimRows(limit: Int) = limit / 10

/**
 * One reading of a tab's source (see [SourceText]) and everything the tools do with it. `terminal_read`, `terminal_input` and `wait_for` all go
 * through here, so a read and an event see the same text.
 *
 * A cursor stands on the row the terminal's cursor is on: that row is the one still being written (a prompt that is typed on, a progress bar), so
 * it is part of what comes AFTER the cursor, and the checksum is of the rows above it. Reading from a cursor places it again:
 * - exactly, by its row number, when the source cannot have dropped rows since ([SourceText.noDrop] and no fewer rows of history than at the
 *   cursor), after checking the checksum there;
 * - otherwise, in a window read of tmux's history ([SourceText.trim]), only at the cursor's own row or one trim above it (tmux drops a tenth of its
 *   history at once), and only when that is the ONE position of the window that holds the checksum; a match anywhere else, or besides it, is not trusted;
 * - otherwise (the whole history, or the app's buffer) by the checksum alone, and only when exactly ONE position holds it: no match, several matches (repeated rows, a full-history loop),
 *   or rows that were not read, mean the rows are no longer told apart and nothing is guessed ([since] is null).
 */
internal class TabText(private val source: SourceText) {
    private val rows: List<String> = if (source.text.isEmpty()) emptyList() else cleanRows(source.text)
    private val first = source.firstRow

    /** The cursor of the end of the output now. */
    val cursor: TabCursor get() = TabCursor(source.cursorRow, source.historyRows, checksum(cleanAbove()))

    /** What a read without `from` shows: the rows read, trailing blank rows cut. */
    fun screen(): String = rows.dropLastWhile { it.isEmpty() }.joinToString("\n")

    /** The rows from [from] on, trailing blank rows cut; null when [from] cannot be placed (see the class text). */
    fun since(from: TabCursor): String? {
        if (first < 0) return null
        val at = when {
            placedByRow(from) -> from.position.takeIf { sumAt(it) == from.sum }
            source.trim >= 0 -> (maxOf(0, first)..first + rows.size).filter { sumAt(it) == from.sum }.singleOrNull()?.takeIf { it == from.position || it == from.position - source.trim }
            else -> {
                var found = -1
                for (q in 0..minOf(from.position, first + rows.size)) {
                    if (sumAt(q) != from.sum) continue
                    if (found >= 0) return null
                    found = q
                }
                found.takeIf { it >= 0 }
            }
        } ?: return null
        if (at - first > rows.size) return null
        return rows.subList(maxOf(0, at - first), rows.size).dropLastWhile { it.isEmpty() }.joinToString("\n")
    }

    /** Whether the rows read reach back to the three rows above [from]: a read that does not cannot place it, and a wider read may. */
    fun covers(from: TabCursor) = first == 0 || (first in 0..maxOf(0, from.position - ANCHOR_ROWS))

    /** Whether [since] placed [from] by its row number: a read that could not do so (null) cannot be helped by reading more rows. */
    fun placedByRow(from: TabCursor) = source.noDrop && source.historyRows >= from.history

    private fun cleanAbove(): List<String> = if (source.above.isEmpty()) emptyList() else cleanRows(source.above.joinToString("\n"))

    /** The checksum of the rows above row [q], or null when they were not read. */
    private fun sumAt(q: Int): Int? {
        val from = maxOf(0, q - ANCHOR_ROWS)
        if (from < first || q - first > rows.size) return null
        return checksum(rows.subList(from - first, q - first))
    }

    private companion object {
        const val ANCHOR_ROWS = 3

        /** FNV-1a over the number of rows and the rows. */
        fun checksum(rows: List<String>): Int {
            var hash = -0x7ee3623b // 2166136261
            hash = (hash xor rows.size) * 16777619
            for (row in rows) {
                for (c in row) hash = (hash xor c.code) * 16777619
                hash = (hash xor '\n'.code) * 16777619
            }
            return hash
        }
    }
}
