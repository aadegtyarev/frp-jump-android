package ru.adsrv.frpjump.assistant

/**
 * A terminal as a tab's output source shows it, in memory: [lines] are every printed row, oldest first, the last [screenRows] of them (padded with
 * blank rows below when there are fewer) are the visible screen and the rest is history; the terminal's cursor is on the last line printed.
 * [tmux] makes it answer like the device's tmux ([exec]: the commands of `TmuxCommands`, which drops a tenth of its history at once when
 * [historyLimit] is reached); otherwise it answers like the app's buffer ([source]: one row at a time). No emulator, no connection.
 */
internal class FakeTerminal(var screenRows: Int = 24, var historyLimit: Int = 2000, private val tmux: Boolean = true) {
    val lines = ArrayList<String>()
    var inCopyMode = false

    val history get() = maxOf(0, lines.size - screenRows)
    private val cursorY get() = if (lines.isEmpty()) 0 else lines.size - 1 - history

    /** Every physical row: history, then the screen, blank rows below the last line printed. */
    private fun all(): List<String> = lines + List(maxOf(0, screenRows - lines.size)) { "" }

    fun print(vararg text: String): FakeTerminal {
        for (line in text) {
            lines += line
            if (tmux) { if (history >= historyLimit) repeat(maxOf(1, historyLimit / 10)) { lines.removeAt(0) } }
            else if (history > historyLimit) lines.removeAt(0)
        }
        return this
    }

    /** Everything on the terminal is [text] (a row per line); the history goes. */
    fun set(text: String): FakeTerminal { lines.clear(); if (text.isNotEmpty()) lines += text.split('\n'); return this }

    /** What a `clear` that also clears the history leaves. */
    fun clear(): FakeTerminal { lines.clear(); return this }

    /** The cursor of the end of the output as a read now would hand it out. */
    fun cursor(): TabCursor = TabText(source(ReadMode.Screen(0))).cursor

    private fun above(): List<String> = all().subList(maxOf(0, history + cursorY - 3), history + cursorY)

    // ---- the app's buffer ----

    fun source(mode: ReadMode): SourceText {
        val rows = all()
        val noDrop = history < historyLimit
        when (mode) {
            is ReadMode.Screen -> return SourceText(rows.subList(history - minOf(mode.lines, history), rows.size).joinToString("\n"), -1, history + cursorY, above(), history, noDrop)
            is ReadMode.From -> {
                val first = firstRow(mode.cursor, mode.full, noDrop && history >= mode.cursor.history)
                return SourceText(rows.subList(minOf(first, rows.size), rows.size).joinToString("\n"), first, history + cursorY, above(), history, noDrop)
            }
        }
    }

    /** Where a read from [cursor] starts: three rows above it; one trim of the history higher when rows may have dropped; the oldest row when [full]. */
    private fun firstRow(cursor: TabCursor, full: Boolean, exact: Boolean): Int {
        if (full) return 0
        val above3 = maxOf(0, cursor.position - 3)
        return if (exact) above3 else maxOf(0, above3 - historyLimit / 10)
    }

    // ---- the device's tmux ----

    /** Called after the captures and before the last numbers of a tmux read: output that comes in between (the numbers then differ). */
    var gap: ((String) -> Unit)? = null

    /** The commands the tmux reads sent, with the rows above the cursor taken (a read of an empty history takes none). */
    var aboveCaptures = 0

    private fun numbers() = "$history $historyLimit $cursorY $screenRows"

    /** What the device answers to the tmux read commands; null for any other command. */
    fun exec(command: String): String? {
        val m = ARGS.find(command) ?: return null
        val rows = all()
        val start = numbers()
        val out = StringBuilder()
        val first = if (m.groupValues[2].isEmpty()) history - minOf(m.groupValues[1].toInt(), history)
            else firstRow(TabCursor(m.groupValues[1].toInt(), m.groupValues[2].toInt(), 0), m.groupValues[3] == "1",
                history.toLong() * 10 < historyLimit.toLong() * 9 && history >= m.groupValues[2].toInt())
        for (row in rows.subList(minOf(first, rows.size), rows.size)) out.append(row).append('\n')
        if (history + cursorY > 0) { aboveCaptures++; for (row in above()) out.append(row).append('\n') }
        gap?.invoke(command)
        return out.append(start).append('\n').append("${numbers()} ${if (inCopyMode) 1 else 0}\n").toString()
    }

    private companion object {
        val ARGS = Regex(" sh '=[^']*' (\\d+)(?: (\\d+) ([01]))?$")
    }
}
