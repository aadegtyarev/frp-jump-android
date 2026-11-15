package ru.adsrv.frpjump.assistant

/**
 * What a tab's ONE output source holds now: [text], the rows oldest first with the visible screen last (a tmux tab's own history and
 * screen from the device, any other tab's terminal buffer from the app), and the number of rows of that screen. Nothing else keeps a tab's
 * output: no second buffer, no log.
 */
internal class SourceText(val text: String, val screenRows: Int)

/**
 * A position in a tab's output, as the model sees it: opaque text, `<rows>.<anchor>`. [position] is how many rows (up to the last one with text)
 * the source held, [anchor] a checksum of the rows just before the last one; [TabText.since] finds the position again by it when the oldest rows
 * were dropped meanwhile (history limit). The model only hands it back.
 */
internal class TabCursor(val position: Int, val anchor: Int) {
    override fun toString() = "$position.${"%08x".format(anchor)}"
    override fun equals(other: Any?) = other is TabCursor && other.position == position && other.anchor == anchor
    override fun hashCode() = 31 * position + anchor

    companion object {
        /** The longest cursor text. */
        const val MAX_CHARS = 32
        private val FORMAT = Regex("(\\d{1,9})\\.([0-9a-f]{8})")

        /** The cursor [raw] spells, or null when it is not one this app made. */
        fun parse(raw: String): TabCursor? {
            val m = FORMAT.matchEntire(raw) ?: return null
            return TabCursor(m.groupValues[1].toInt(), m.groupValues[2].toLong(16).toInt())
        }
    }
}

/**
 * One reading of a tab's source (see [SourceText]) and everything the tools do with it: the visible screen with some history, the cursor of its
 * end, and the rows after an earlier cursor. `terminal_read`, `terminal_input` and `wait_for` all go through here, so a read and an event see the
 * same text.
 *
 * Rows are the source's rows with wrapped ones joined, cleaned for the model ([cleanRows]). A cursor counts rows up to the last one with text:
 * blank rows of the screen below it are not yet anything. The LAST counted row may still be written to (a prompt that is typed on), so it is not
 * part of the cursor's anchor and an update of it does not count as output after the cursor.
 */
internal class TabText(source: SourceText) {
    private val rows: List<String> = cleanRows(source.text.removeSuffix("\n"))
    private val screenRows = source.screenRows
    private val end = rows.indexOfLast { it.isNotEmpty() } + 1

    /** The cursor of the end of the output now. */
    val cursor: TabCursor get() = TabCursor(end, anchorAt(end))

    /** The visible screen with up to [history] rows before it, trailing blank rows cut. */
    fun screen(history: Int): String = join(maxOf(0, rows.size - screenRows - history), end)

    /** Every row the source holds. */
    fun all(): String = join(0, end)

    /**
     * The rows printed after [cursor]; null when they cannot be told: the rows before the cursor are gone, which is when its anchor is found at no
     * position at or before the cursor's own (the oldest rows were dropped, so the same rows now sit higher up), or nowhere.
     */
    fun since(cursor: TabCursor): String? {
        for (q in minOf(cursor.position, rows.size) downTo 0) if (anchorAt(q) == cursor.anchor) return join(q, end)
        return null
    }

    private fun join(from: Int, to: Int) = if (from >= to) "" else rows.subList(from, to).joinToString("\n")

    /** FNV-1a over the [ANCHOR_ROWS] rows before row `position - 1` (that one may still change). */
    private fun anchorAt(position: Int): Int {
        val to = maxOf(0, position - 1)
        var hash = -0x7ee3623b // 2166136261
        for (i in maxOf(0, to - ANCHOR_ROWS) until to) {
            for (c in rows[i]) hash = (hash xor c.code) * 16777619
            hash = (hash xor '\n'.code) * 16777619
        }
        return hash
    }

    private companion object { const val ANCHOR_ROWS = 3 }
}
